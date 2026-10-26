package com.aipb.widget;

import com.aipb.identity.Actor;
import com.aipb.identity.Role;
import com.aipb.service.LayoutService;
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
    private LayoutService layouts;
    private static final Actor BUILDER = Actor.of(Role.BUILDER);
    private static final Actor REVIEWER = Actor.of(Role.REVIEWER);
    private static final Actor BANKER = Actor.of(Role.BANKER);

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
        when(library.publish(any(), anyInt(), any(), any(), any(), any(), any(), any())).thenAnswer(i -> {
            WidgetLibrary.Checked c = i.getArgument(0);
            WidgetDefinition d = widgets.getOrDefault(c.parsed().id(), new WidgetDefinition());
            d.setId(c.parsed().id());
            d.setTitle(c.parsed().title());
            d.setVersion(i.getArgument(1));
            if (d.getOrigin() == null) d.setOrigin(i.getArgument(2));
            d.setScope(WidgetDefinition.GLOBAL);
            widgets.put(d.getId(), d);
            return d;
        });
        ApplicationEventPublisher events = e -> decisions.add((WidgetWorkflow.Decision) e);
        layouts = mock(LayoutService.class);
        when(library.get(anyString(), anyString())).thenAnswer(i -> Optional.ofNullable(widgets.get((String) i.getArgument(0)))
                .filter(d -> d.visibleTo(i.getArgument(1))).orElseThrow(() -> new NoSuchElementException("widget " + i.getArgument(0))));
        when(library.spec(any(WidgetDefinition.class))).thenAnswer(i -> spec(((WidgetDefinition) i.getArgument(0)).getId(), "Personal"));
        when(library.submitForReview(anyString(), anyString(), any())).thenAnswer(i -> {
            WidgetDefinition d = widgets.get((String) i.getArgument(0));
            WidgetDefinition.Review r = new WidgetDefinition.Review();
            r.setStatus("pending");
            r.setSubmittedBy(i.getArgument(1));
            r.setNote(i.getArgument(2));
            d.setReview(r);
            return d;
        });
        workflow = new WidgetWorkflow(library, layouts, submissions, mock(WidgetSpecValidator.class), events, mapper);
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

    private void personal(String id, String owner) {
        WidgetDefinition d = new WidgetDefinition();
        d.setId(id);
        d.setTitle("Personal " + id);
        d.setOwner(owner);
        d.setScope(WidgetDefinition.PERSONAL);
        widgets.put(id, d);
    }

    @Test
    void sharingAPersonalWidgetWaitsForReviewAndApprovalTakesItLiveWhereItWasPlaced() {
        personal("mine", "demo-builder");
        WidgetSubmission s = workflow.share("mine", BUILDER, "checked against the finance pack", "assistant", "session-1", "default", "client", Map.of());
        assertThat(s.getStatus()).isEqualTo(WidgetSubmission.PENDING);
        assertThat(s.getBaseVersion()).isNull();
        assertThat(s.getHistory().get(0).getComment()).isEqualTo("checked against the finance pack");
        verify(library).markReview("mine", "pending", s.getId());
        verify(layouts, never()).openPlacements(any());

        workflow.approve(s.getId(), REVIEWER, null);
        verify(library).publish(any(), eq(1), eq(WidgetDefinition.BUILT), eq(WidgetVersion.APPROVED), eq(s.getId()), isNull(), eq("reviewer"), eq("demo-builder"));
        verify(layouts).openPlacements("mine");   // the Builder's personal placement is now live for everyone
        assertThat(widgets.get("mine").global()).isTrue();
    }

    @Test
    void aRejectedPersonalWidgetComesOffEveryPageAndStaysPersonal() {
        personal("mine", "demo-builder");
        WidgetSubmission s = workflow.share("mine", BUILDER, null, "assistant", null, "default", "client", Map.of());
        workflow.reject(s.getId(), REVIEWER, "Duplicates an existing widget");
        verify(layouts).removePlacements("mine");
        verify(library).markReview("mine", "rejected", s.getId());
        verify(library, never()).publish(any(), anyInt(), any(), any(), any(), any(), any(), any());
        assertThat(widgets.get("mine").global()).isFalse();
    }

    @Test
    void afterFeedbackSharingAgainResubmitsTheSameReview() {
        personal("mine", "demo-builder");
        WidgetSubmission s = workflow.share("mine", BUILDER, null, "assistant", null, "default", "client", Map.of());
        workflow.sendBack(s.getId(), REVIEWER, "Use 12 months");
        verify(library).markReview("mine", "changes_requested", s.getId());
        widgets.get("mine").getReview().setStatus("changes_requested");
        WidgetSubmission again = workflow.share("mine", BUILDER, null, "assistant", null, "default", "client", Map.of());
        assertThat(again.getId()).isEqualTo(s.getId());
        assertThat(again.getStatus()).isEqualTo(WidgetSubmission.PENDING);
        assertThat(again.getHistory()).extracting(WidgetSubmission.Event::getType).containsExactly("submitted", "sent_back", "resubmitted");
    }

    @Test
    void someoneElsesPersonalWidgetCannotBeShared() {
        personal("theirs", "demo-reviewer");
        assertThatThrownBy(() -> workflow.share("theirs", BUILDER, null, "assistant", null, "default", "client", Map.of()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void aBuildersWidgetWaitsAndNothingIsPublished() {
        WidgetSubmission s = workflow.submit(newWidget("w"), BUILDER);
        assertThat(s.getStatus()).isEqualTo(WidgetSubmission.PENDING);
        assertThat(s.getBaseVersion()).isNull();
        verify(library, never()).publish(any(), anyInt(), any(), any(), any(), any(), any(), any());
        assertThat(decisions).extracting(WidgetWorkflow.Decision::type).containsExactly("submitted");
    }

    @Test
    void anApproversWidgetIsApprovedAsVersionOne() {
        WidgetSubmission s = workflow.submit(newWidget("w"), REVIEWER);
        assertThat(s.getStatus()).isEqualTo(WidgetSubmission.APPROVED);
        assertThat(s.getApprovedVersion()).isEqualTo(1);
        verify(library).publish(any(), eq(1), eq(WidgetDefinition.BUILT), eq(WidgetVersion.APPROVED), eq(s.getId()), isNull(), eq("reviewer"), any());
        assertThat(s.getHistory()).extracting(WidgetSubmission.Event::getType).containsExactly("submitted", "approved");
        assertThat(s.getSubmittedBy()).isEqualTo("demo-reviewer");
    }

    @Test
    void onlyAnApproverDecides() {
        WidgetSubmission s = workflow.submit(newWidget("w"), BUILDER);
        assertThatThrownBy(() -> workflow.approve(s.getId(), BUILDER, null)).isInstanceOf(Actor.Forbidden.class);
        assertThatThrownBy(() -> workflow.sendBack(s.getId(), BUILDER, "x")).isInstanceOf(Actor.Forbidden.class);
        assertThatThrownBy(() -> workflow.reject(s.getId(), BUILDER, "x")).isInstanceOf(Actor.Forbidden.class);
        assertThatThrownBy(() -> workflow.restore("w", 1, BUILDER)).isInstanceOf(Actor.Forbidden.class);
    }

    @Test
    void aBankerCannotBuildAndOnlyTheBuilderWhoSubmittedCanRevise() {
        assertThatThrownBy(() -> workflow.submit(newWidget("w"), BANKER)).isInstanceOf(Actor.Forbidden.class);
        WidgetSubmission s = workflow.submit(newWidget("w"), BUILDER);
        assertThat(s.getSubmittedBy()).isEqualTo("demo-builder");
        Actor otherBuilder = new Actor("someone-else", Role.BUILDER);
        assertThatThrownBy(() -> workflow.resubmit(s.getId(), spec("w", "x"), otherBuilder)).isInstanceOf(Actor.Forbidden.class);
        assertThat(workflow.skills(otherBuilder).get(0).get("items")).asList().isEmpty();
        assertThat(workflow.skills(BANKER)).extracting(m -> m.get("key")).containsExactly("approved", "stock");
    }

    @Test
    void feedbackAndReviseNeedsACommentAndTheResubmissionWaitsAgain() {
        WidgetSubmission s = workflow.submit(newWidget("w"), BUILDER);
        assertThatThrownBy(() -> workflow.sendBack(s.getId(), REVIEWER, " ")).isInstanceOf(IllegalArgumentException.class);
        workflow.sendBack(s.getId(), REVIEWER, "Use 12 months");
        assertThat(store.get(s.getId()).getStatus()).isEqualTo(WidgetSubmission.CHANGES_REQUESTED);
        assertThat(store.get(s.getId()).lastComment()).isEqualTo("Use 12 months");
        assertThatThrownBy(() -> workflow.approve(s.getId(), REVIEWER, null)).isInstanceOf(WidgetWorkflow.Conflict.class);

        WidgetSubmission again = workflow.resubmit(s.getId(), spec("renamed-by-model", "Revised"), BUILDER);
        assertThat(again.getStatus()).isEqualTo(WidgetSubmission.PENDING);
        assertThat(again.getWidgetId()).isEqualTo("w");
        assertThat(again.getTitle()).isEqualTo("Revised");
        assertThat(workflow.spec(again).path("id").asText()).isEqualTo("w");

        workflow.approve(s.getId(), REVIEWER, "Looks right");
        assertThat(store.get(s.getId()).getApprovedVersion()).isEqualTo(1);
        assertThat(store.get(s.getId()).getHistory()).extracting(WidgetSubmission.Event::getType)
                .containsExactly("submitted", "sent_back", "resubmitted", "approved");
    }

    @Test
    void aRejectionIsFinalAndNeedsAReason() {
        WidgetSubmission s = workflow.submit(newWidget("w"), BUILDER);
        assertThatThrownBy(() -> workflow.reject(s.getId(), REVIEWER, null)).isInstanceOf(IllegalArgumentException.class);
        workflow.reject(s.getId(), REVIEWER, "Duplicates the revenue widget");
        assertThatThrownBy(() -> workflow.resubmit(s.getId(), spec("w", "x"), BUILDER)).isInstanceOf(WidgetWorkflow.Conflict.class);
        assertThatThrownBy(() -> workflow.approve(s.getId(), REVIEWER, null)).isInstanceOf(WidgetWorkflow.Conflict.class);
    }

    @Test
    void aChangeToAStockWidgetBecomesItsNextVersionAndOnlyOneChangeWaitsAtATime() {
        stock("client-exposure", 4);
        WidgetWorkflow.Submit change = new WidgetWorkflow.Submit(spec("any-id", "Exposure v2"), "assistant", "s", "default", "client",
                Map.of(), "client-exposure");
        WidgetSubmission s = workflow.submit(change, BUILDER);
        assertThat(s.getWidgetId()).isEqualTo("client-exposure");
        assertThat(s.getBaseVersion()).isEqualTo(4);
        assertThatThrownBy(() -> workflow.submit(change, BUILDER)).isInstanceOf(WidgetWorkflow.Conflict.class);
        assertThat(workflow.idTaken("client-exposure")).isTrue();

        workflow.approve(s.getId(), REVIEWER, null);
        assertThat(widgets.get("client-exposure").getVersion()).isEqualTo(5);
        assertThat(widgets.get("client-exposure").getOrigin()).isEqualTo(WidgetDefinition.STOCK); // still listed as stock
    }

    @Test
    void aNewWidgetCannotTakeAnExistingId() {
        stock("client-exposure", 1);
        assertThatThrownBy(() -> workflow.submit(newWidget("client-exposure"), BUILDER)).isInstanceOf(WidgetLibrary.DuplicateException.class);
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

        assertThatThrownBy(() -> workflow.restore("client-exposure", 3, REVIEWER)).isInstanceOf(IllegalArgumentException.class);
        workflow.restore("client-exposure", 1, REVIEWER);
        verify(library).publish(any(), eq(4), eq(WidgetDefinition.STOCK), eq(WidgetVersion.RESTORE), isNull(), eq(1), eq("reviewer"), any());
        assertThat(widgets.get("client-exposure").getTitle()).isEqualTo("Exposure as it was");
    }

    @Test
    void skillsListsSectionsInTheOrderEachRoleSeesThem() {
        stock("client-exposure", 1);
        when(library.list()).thenAnswer(i -> new ArrayList<>(widgets.values()));
        WidgetSubmission pending = workflow.submit(newWidget("a"), BUILDER);
        WidgetSubmission back = workflow.submit(newWidget("b"), BUILDER);
        workflow.sendBack(back.getId(), REVIEWER, "fix");
        workflow.submit(newWidget("c"), REVIEWER);

        List<Map<String, Object>> builder = workflow.skills(BUILDER);
        assertThat(builder).extracting(m -> m.get("key")).containsExactly("mine", "approved", "stock");
        assertThat(ids(builder.get(0))).containsExactlyInAnyOrder(pending.getId(), back.getId());
        assertThat(ids(builder.get(1))).containsExactly("c");
        assertThat(ids(builder.get(2))).containsExactly("client-exposure");

        List<Map<String, Object>> approver = workflow.skills(REVIEWER);
        assertThat(approver).extracting(m -> m.get("key")).containsExactly("review", "approved", "stock");
        assertThat(ids(approver.get(0))).containsExactly(pending.getId());
        assertThat(workflow.attention(REVIEWER)).isEqualTo(1);
        assertThat(workflow.attention(BUILDER)).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> ids(Map<String, Object> section) {
        return ((List<Map<String, Object>>) section.get("items")).stream().map(m -> m.get("id")).toList();
    }
}
