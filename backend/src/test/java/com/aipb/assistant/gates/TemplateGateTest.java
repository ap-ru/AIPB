package com.aipb.assistant.gates;

import com.aipb.skills.SkillTemplate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TemplateGateTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TemplateGate gate = new TemplateGate();

    private final SkillTemplate exposure = new SkillTemplate("exposure-vs-limit", 1, "widget", "Exposure vs. limit", "s", "gauge", "Credit",
            List.of("client"), null, null,
            new SkillTemplate.Constraints(List.of("meter", "bar"), List.of("holdings"), null, "client", null, 25),
            new SkillTemplate.Defaults(Map.of("display", "meter")), null, null);
    private final SkillTemplate custom = new SkillTemplate("custom-widget", 1, "widget", "Custom widget", "s", "spark", "General",
            List.of("client", "book"), null, null, null, null, null, null);

    private JsonNode json(String s) {
        try { return mapper.readTree(s); } catch (Exception e) { throw new IllegalStateException(e); }
    }

    @Test
    void aTemplateWithoutConstraintsNeverObjects() {
        assertThat(gate.intake(json("{\"scope\":\"book\",\"display\":\"table\"}"), custom)).isEmpty();
        assertThat(gate.build(json("{\"collection\":\"signals\"}"), custom)).isEmpty();
        assertThat(gate.rows(json("{\"preview\":{\"count\":5000}}"), custom)).isEmpty();
        assertThat(gate.present(json("{\"primitive\":\"list\"}"), custom)).isEmpty();
        JsonNode brief = json("{\"display\":\"any\",\"assumptions\":[]}");
        assertThat(gate.withDefaults(brief, custom)).isEqualTo(brief);
    }

    @Test
    void eachStageIsHeldToTheTemplate() {
        assertThat(gate.intake(json("{\"scope\":\"book\",\"display\":\"any\"}"), exposure)).singleElement().asString().contains("client-scope");
        assertThat(gate.intake(json("{\"scope\":\"client\",\"display\":\"line\"}"), exposure)).singleElement().asString().contains("meter / bar only");
        assertThat(gate.intake(json("{\"scope\":\"client\",\"display\":\"bar\"}"), exposure)).isEmpty();
        assertThat(gate.build(json("{\"collection\":\"revenue_monthly\"}"), exposure)).singleElement().asString().contains("reads holdings only");
        assertThat(gate.build(json("{\"collection\":\"holdings\"}"), exposure)).isEmpty();
        assertThat(gate.rows(json("{\"preview\":{\"count\":26}}"), exposure)).singleElement().asString().contains("at most 25");
        assertThat(gate.rows(json("{\"preview\":{\"count\":25}}"), exposure)).isEmpty();
        assertThat(gate.present(json("{\"primitive\":\"table\"}"), exposure)).singleElement().asString().contains("not table");
    }

    @Test
    void defaultsFillOnlyWhatIntakeLeftOpenAndAreDisclosed() {
        JsonNode open = gate.withDefaults(json("{\"display\":\"any\",\"assumptions\":[\"x\"]}"), exposure);
        assertThat(open.path("display").asText()).isEqualTo("meter");
        assertThat(open.path("assumptions").toString()).contains("display: meter (the Exposure vs. limit template's default)");
        JsonNode chosen = gate.withDefaults(json("{\"display\":\"bar\",\"assumptions\":[]}"), exposure);
        assertThat(chosen.path("display").asText()).isEqualTo("bar");
        assertThat(chosen.path("assumptions")).isEmpty();
    }
}
