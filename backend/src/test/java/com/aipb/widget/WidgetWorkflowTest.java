package com.aipb.widget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Maker-checker transitions, role checks and version numbering, over an in-memory submission store. */
class WidgetWorkflowTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, WidgetSubmission> store = new HashMap<>();
    private final Map<String, WidgetDefinition> widgets = new HashMap<>();
    private final List<WidgetWorkflow.Decision> decisions = new ArrayList<>();
    private WidgetLibrary library;
    private WidgetWorkflow workflow;

    @BeforeEach
    void setUp() {
        library = mock(WidgetLibrary.class);
        WidgetSubmissionRepository submissions = mock(WidgetSubmissionRepository.class);
        when(submissions.save(any())).thenAnswer(i -> { WidgetSubmission s = i.getArgument(0); store.put(s.getId(), s); return s; });
        when(submissions.findById(anyString())).thenAnswer(i -> Optional.ofNullable(store.get((String) i.getArgument(0))));
        when(submissions.findByStatusIn(any())).thenAnswer(i -> {
            Collection<String> st = i.getArgument(0);
            return store.values().stream().filter(s -> st.contains(s.getStatus())).toList();
        });
        when(submissions.findByWidgetIdAndStatusIn(anyString(), any())).thenAnswer(i -> {
            Collection<String> st = i.getArgument(1);
            return store.values().stream().filter(s -> s.getWidgetId().equals(i.getArgument(0)) && st.contains(s.getStatus())).toList();
        });
        when(library.check(any(), anyInt(), any(), any(), any(), any())).thenAnswer(i -> {
            ObjectNode spec = (ObjectNode) SpecMigrator.migrate(i.getArgument(0));
            return new WidgetLibrary.Checked(spec, SpecMigrator.read(mapper, spec), null);
        });
        when(library.exists(anyString())).thenAnswer(i -> widgets.containsKey((String) i.getArgument(0)));
        when(library.find(anyString())).thenAnswer(i -> Optional.ofNullable(widgets.get((String) i.getArgument(0))));
        when(library.get(anyString())).thenAnswer(i -> Optional.ofNullable(widgets.get((String) i.getArgument(0)))
                .orElseThrow(() -> new NoSuchElementException("widget " + i.getArgument(0))));
        when(library.publish(any(), anyInt(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            WidgetLibrary.Checked c = i.getArgument(0);
            WidgetDefinition d = widgets.getOrDefault(c.parsed().id(), new WidgetDefinition());
            d.setId(c.parsed().id());
            d.setTitle(c.parsed().title());
            d.setVersion(i.getArgument(1));
            if (d.getOrigin() == null) d.setOrigin(i.getArgument(2));
            widgets.put(d.getId(), d);
            return d;
        });
        ApplicationEventPublisher events = e -> decisions.add((WidgetWorkflow.Decision) e);
        workflow = new WidgetWorkflow(library, submissions, mock(WidgetSpecValidator.class), events, mapper);
    }

    private JsonNode spec(String id, String title) {
        try {
            return mapper.readTree("{\"id\":\"" + id + "\",\"title\":\"" + title + "\",\"description\":\"d\",\"data\":{\"collection\":\"c\","
                    + "\"pipeline\":[{}],\"columns\":[]},\"view\":{\"primitive\":\"bar\",\"x\":\"q\",\"y\":[\"v\"]}}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private WidgetWorkflow.Submit newWidget(String id) {
        return new WidgetWorkflow.Submit(spec(id, "Title " + id), "assistant", "session-1", "default", "client", Map.of("clientId", "CL-1"), null);
    }

    private void stock(String id, int version) {
        WidgetDefinition d = new WidgetDefinition();
        d.setId(id);
        d.setTitle("Stock " + id);
        d.setVersion(version);
        d.setOrigin(WidgetDefinition.STOCK);
        widgets.put(id, d);
    }

    @Test
    void aBuildersWidgetWaitsAndNothingIsPublished() {
        WidgetSubmission s = workflow.submit(newWidget("w"), Role.BUILDER);
        assertThat(s.getStatus()).isEqualTo(WidgetSubmission.PENDING);
        assertThat(s.getBaseVersion()).isNull();
        verify(library, never()).publish(any(), anyInt(), any(), any(), any(), any(), any());
        assertThat(decisions).extracting(WidgetWorkflow.Decision::type).containsExactly("submitted");
    }

    @Test
    void anApproversWidgetIsApprovedAsVersionOne() {
        WidgetSubmission s = workflow.submit(newWidget("w"), Role.APPROVER);
        assertThat(s.getStatus()).isEqualTo(WidgetSubmission.APPROVED);
        assertThat(s.getApprovedVersion()).isEqualTo(1);
        verify(library).publish(any(), eq(1), eq(WidgetDefinition.BUILT), eq(WidgetVersion.APPROVED), eq(s.getId()), isNull(), eq("approver"));
        assertThat(s.getHistory()).extracting(WidgetSubmission.Event::getType).containsExactly("submitted", "approved");
    }

    @Test
    void onlyAnApproverDecides() {
        WidgetSubmission s = workflow.submit(newWidget("w"), Role.BUILDER);
        assertThatThrownBy(() -> workflow.approve(s.getId(), Role.BUILDER, null)).isInstanceOf(Role.Forbidden.class);
        assertThatThrownBy(() -> workflow.sendBack(s.getId(), Role.BUILDER, "x")).isInstanceOf(Role.Forbidden.class);
        assertThatThrownBy(() -> workflow.reject(s.getId(), Role.BUILDER, "x")).isInstanceOf(Role.Forbidden.class);
        assertThatThrownBy(() -> workflow.restore("w", 1, Role.BUILDER)).isInstanceOf(Role.Forbidden.class);
    }

    @Test
    void sendBackNeedsACommentAndTheResubmissionWaitsAgain() {
        WidgetSubmission s = workflow.submit(newWidget("w"), Role.BUILDER);
        assertThatThrownBy(() -> workflow.sendBack(s.getId(), Role.APPROVER, " ")).isInstanceOf(IllegalArgumentException.class);
        workflow.sendBack(s.getId(), Role.APPROVER, "Use 12 months");
        assertThat(store.get(s.getId()).getStatus()).isEqualTo(WidgetSubmission.CHANGES_REQUESTED);
        assertThat(store.get(s.getId()).lastComment()).isEqualTo("Use 12 months");
        assertThatThrownBy(() -> workflow.approve(s.getId(), Role.APPROVER, null)).isInstanceOf(WidgetWorkflow.Conflict.class);

        WidgetSubmission again = workflow.resubmit(s.getId(), spec("renamed-by-model", "Revised"), Role.BUILDER);
        assertThat(again.getStatus()).isEqualTo(WidgetSubmission.PENDING);
        assertThat(again.getWidgetId()).isEqualTo("w");
        assertThat(again.getTitle()).isEqualTo("Revised");
        assertThat(workflow.spec(again).path("id").asText()).isEqualTo("w");

        workflow.approve(s.getId(), Role.APPROVER, "Looks right");
        assertThat(store.get(s.getId()).getApprovedVersion()).isEqualTo(1);
        assertThat(store.get(s.getId()).getHistory()).extracting(WidgetSubmission.Event::getType)
                .containsExactly("submitted", "sent_back", "resubmitted", "approved");
    }

    @Test
    void aRejectionIsFinalAndNeedsAReason() {
        WidgetSubmission s = workflow.submit(newWidget("w"), Role.BUILDER);
        assertThatThrownBy(() -> workflow.reject(s.getId(), Role.APPROVER, null)).isInstanceOf(IllegalArgumentException.class);
        workflow.reject(s.getId(), Role.APPROVER, "Duplicates the revenue widget");
        assertThatThrownBy(() -> workflow.resubmit(s.getId(), spec("w", "x"), Role.BUILDER)).isInstanceOf(WidgetWorkflow.Conflict.class);
        assertThatThrownBy(() -> workflow.approve(s.getId(), Role.APPROVER, null)).isInstanceOf(WidgetWorkflow.Conflict.class);
    }

    @Test
    void aChangeToAStockWidgetBecomesItsNextVersionAndOnlyOneChangeWaitsAtATime() {
        stock("client-exposure", 4);
        WidgetWorkflow.Submit change = new WidgetWorkflow.Submit(spec("any-id", "Exposure v2"), "assistant", "s", "default", "client",
                Map.of(), "client-exposure");
        WidgetSubmission s = workflow.submit(change, Role.BUILDER);
        assertThat(s.getWidgetId()).isEqualTo("client-exposure");
        assertThat(s.getBaseVersion()).isEqualTo(4);
        assertThatThrownBy(() -> workflow.submit(change, Role.BUILDER)).isInstanceOf(WidgetWorkflow.Conflict.class);
        assertThat(workflow.idTaken("client-exposure")).isTrue();

        workflow.approve(s.getId(), Role.APPROVER, null);
        assertThat(widgets.get("client-exposure").getVersion()).isEqualTo(5);
        assertThat(widgets.get("client-exposure").getOrigin()).isEqualTo(WidgetDefinition.STOCK); // still listed as stock
    }

    @Test
    void aNewWidgetCannotTakeAnExistingId() {
        stock("client-exposure", 1);
        assertThatThrownBy(() -> workflow.submit(newWidget("client-exposure"), Role.BUILDER)).isInstanceOf(WidgetLibrary.DuplicateException.class);
    }

    @Test
    void restoreRepublishesAnOldVersionAsTheNextOne() {
        stock("client-exposure", 3);
        WidgetVersion v1 = new WidgetVersion();
        v1.setWidgetId("client-exposure");
        v1.setVersion(1);
        when(library.version("client-exposure", 1)).thenReturn(v1);
        when(library.spec(v1)).thenReturn(spec("client-exposure", "Exposure as it was"));
        when(library.sampleParams(any())).thenReturn(Map.of());

        assertThatThrownBy(() -> workflow.restore("client-exposure", 3, Role.APPROVER)).isInstanceOf(IllegalArgumentException.class);
        workflow.restore("client-exposure", 1, Role.APPROVER);
        verify(library).publish(any(), eq(4), eq(WidgetDefinition.STOCK), eq(WidgetVersion.RESTORE), isNull(), eq(1), eq("approver"));
        assertThat(widgets.get("client-exposure").getTitle()).isEqualTo("Exposure as it was");
    }

    @Test
    void skillsListsSectionsInTheOrderEachRoleSeesThem() {
        stock("client-exposure", 1);
        when(library.list()).thenAnswer(i -> new ArrayList<>(widgets.values()));
        WidgetSubmission pending = workflow.submit(newWidget("a"), Role.BUILDER);
        WidgetSubmission back = workflow.submit(newWidget("b"), Role.BUILDER);
        workflow.sendBack(back.getId(), Role.APPROVER, "fix");
        workflow.submit(newWidget("c"), Role.APPROVER);

        List<Map<String, Object>> builder = workflow.skills(Role.BUILDER);
        assertThat(builder).extracting(m -> m.get("key")).containsExactly("mine", "approved", "stock");
        assertThat(ids(builder.get(0))).containsExactlyInAnyOrder(pending.getId(), back.getId());
        assertThat(ids(builder.get(1))).containsExactly("c");
        assertThat(ids(builder.get(2))).containsExactly("client-exposure");

        List<Map<String, Object>> approver = workflow.skills(Role.APPROVER);
        assertThat(approver).extracting(m -> m.get("key")).containsExactly("review", "approved", "stock");
        assertThat(ids(approver.get(0))).containsExactly(pending.getId());
        assertThat(workflow.attention(Role.APPROVER)).isEqualTo(1);
        assertThat(workflow.attention(Role.BUILDER)).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> ids(Map<String, Object> section) {
        return ((List<Map<String, Object>>) section.get("items")).stream().map(m -> m.get("id")).toList();
    }
}
