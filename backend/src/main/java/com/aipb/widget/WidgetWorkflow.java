package com.aipb.widget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

/**
 * Maker-checker for the widget library. A builder's widget, or change to a widget, is a {@link WidgetSubmission} that
 * waits for an approver; an approver's own is approved as it is submitted.
 * <pre>
 * submit -> pending -> approve   -> approved (the widget's next version, live on every page that places it)
 *                   -> send back -> changes_requested -> resubmit -> pending
 *                   -> reject    -> rejected (final)
 * </pre>
 * An approver can also restore an older version, which is published again as the next version. A widget has at most
 * one open submission at a time. Each decision is published as a {@link Decision} event, so the assistant conversation
 * a submission came from can show it.
 */
@Service
@RequiredArgsConstructor
public class WidgetWorkflow {

    private final WidgetLibrary library;
    private final WidgetSubmissionRepository submissions;
    private final WidgetSpecValidator validator;
    private final ApplicationEventPublisher events;
    private final ObjectMapper mapper;

    /** A move the workflow does not allow from the submission's current state. */
    public static class Conflict extends RuntimeException {
        public Conflict(String message) { super(message); }
    }

    /** What to submit: the spec, where it was made, and - for a change - the widget it changes. */
    public record Submit(JsonNode spec, String origin, String sessionId, String layoutId, String pageId,
                         Map<String, String> params, String modifies) {}

    /** A decision on a submission (submitted, resubmitted, approved, sent_back, rejected), for listeners. */
    public record Decision(WidgetSubmission submission, String type, Role role, String comment) {}

    // ------------------------------------------------------------------ the flow
    public synchronized WidgetSubmission submit(Submit req, Role role) {
        if (req.spec() == null || !req.spec().isObject()) throw new IllegalArgumentException("a widget spec is required");
        ObjectNode spec = req.spec().deepCopy();
        Integer base = null;
        if (req.modifies() != null) {
            WidgetDefinition current = library.get(req.modifies());
            spec.put("id", current.getId());
            base = current.getVersion();
        } else {
            String id = spec.path("id").asText();
            if (library.exists(id)) throw new WidgetLibrary.DuplicateException("a widget '" + id + "' already exists; change it, or pick another id");
        }
        String widgetId = spec.path("id").asText();
        List<WidgetSubmission> open = submissions.findByWidgetIdAndStatusIn(widgetId, WidgetSubmission.OPEN);
        if (!open.isEmpty())
            throw new Conflict("'" + widgetId + "' already has a change waiting for approval; revise that one instead");

        WidgetLibrary.Checked c = library.check(spec, base == null ? 1 : base + 1, req.origin(), req.layoutId(), req.pageId(), req.params());
        WidgetSubmission s = new WidgetSubmission();
        s.setId(UUID.randomUUID().toString());
        s.setWidgetId(widgetId);
        s.setBaseVersion(base);
        s.setOrigin(req.origin());
        s.setSessionId(req.sessionId());
        s.setLayoutId(req.layoutId() == null ? "default" : req.layoutId());
        s.setPageId(req.pageId());
        if (req.params() != null) s.setParams(new LinkedHashMap<>(req.params()));
        s.setSubmittedByRole(role.key());
        s.setCreatedAt(Instant.now());
        setSpec(s, c);
        record(s, "submitted", role, null);
        submissions.save(s);
        events.publishEvent(new Decision(s, "submitted", role, null));
        return role == Role.APPROVER ? decideApprove(s, role, null) : s;
    }

    /** A revised spec for an open submission (after a send-back, or while it waits); it waits for approval again. */
    public synchronized WidgetSubmission resubmit(String submissionId, JsonNode specIn, Role role) {
        WidgetSubmission s = get(submissionId);
        if (!s.open()) throw new Conflict("this submission is " + label(s.getStatus()) + " and cannot be changed");
        ObjectNode spec = specIn.deepCopy();
        spec.put("id", s.getWidgetId());
        WidgetLibrary.Checked c = library.check(spec, s.getBaseVersion() == null ? 1 : s.getBaseVersion() + 1, s.getOrigin(),
                s.getLayoutId(), s.getPageId(), s.getParams());
        setSpec(s, c);
        s.setStatus(WidgetSubmission.PENDING);
        record(s, "resubmitted", role, null);
        submissions.save(s);
        events.publishEvent(new Decision(s, "resubmitted", role, null));
        return role == Role.APPROVER ? decideApprove(s, role, null) : s;
    }

    public synchronized WidgetSubmission approve(String submissionId, Role role, String comment) {
        role.requireApprover("approve a widget");
        WidgetSubmission s = get(submissionId);
        if (!WidgetSubmission.PENDING.equals(s.getStatus())) throw new Conflict("only a pending submission can be approved; this one is " + label(s.getStatus()));
        return decideApprove(s, role, comment);
    }

    public synchronized WidgetSubmission sendBack(String submissionId, Role role, String comment) {
        role.requireApprover("send a widget back");
        if (comment == null || comment.isBlank()) throw new IllegalArgumentException("say what needs to change");
        WidgetSubmission s = get(submissionId);
        if (!WidgetSubmission.PENDING.equals(s.getStatus())) throw new Conflict("only a pending submission can be sent back; this one is " + label(s.getStatus()));
        s.setStatus(WidgetSubmission.CHANGES_REQUESTED);
        return decide(s, "sent_back", role, comment.trim());
    }

    public synchronized WidgetSubmission reject(String submissionId, Role role, String reason) {
        role.requireApprover("reject a widget");
        if (reason == null || reason.isBlank()) throw new IllegalArgumentException("give a reason for the rejection");
        WidgetSubmission s = get(submissionId);
        if (!s.open()) throw new Conflict("this submission is " + label(s.getStatus()) + " already");
        s.setStatus(WidgetSubmission.REJECTED);
        return decide(s, "rejected", role, reason.trim());
    }

    /** Makes an older version current again, as the widget's next version. Checked against today's data first. */
    public synchronized WidgetDefinition restore(String widgetId, int version, Role role) {
        role.requireApprover("restore a version");
        WidgetDefinition current = library.get(widgetId);
        if (version == current.getVersion()) throw new IllegalArgumentException("v" + version + " is already the current version");
        WidgetVersion old = library.version(widgetId, version);
        JsonNode spec = library.spec(old);
        String author = spec.path("provenance").path("author").asText("restore");
        WidgetLibrary.Checked c = library.check(spec, current.getVersion() + 1, author, "default", null, library.sampleParams(spec));
        return library.publish(c, current.getVersion() + 1, current.getOrigin(), WidgetVersion.RESTORE, null, version, role.key());
    }

    private WidgetSubmission decideApprove(WidgetSubmission s, Role role, String comment) {
        // checked again: the data may have changed since it was submitted
        int version = library.find(s.getWidgetId()).map(d -> d.getVersion() + 1).orElse(1);
        WidgetLibrary.Checked c = library.check(read(s.getSpec()), version, s.getOrigin(), s.getLayoutId(), s.getPageId(), s.getParams());
        library.publish(c, version, WidgetDefinition.BUILT, WidgetVersion.APPROVED, s.getId(), null, role.key());
        s.setApprovedVersion(version);
        s.setSpec(c.spec().toString());
        s.setStatus(WidgetSubmission.APPROVED);
        return decide(s, "approved", role, comment == null || comment.isBlank() ? null : comment.trim());
    }

    private WidgetSubmission decide(WidgetSubmission s, String type, Role role, String comment) {
        record(s, type, role, comment);
        submissions.save(s);
        events.publishEvent(new Decision(s, type, role, comment));
        return s;
    }

    // ------------------------------------------------------------------ reading
    public WidgetSubmission get(String id) {
        return submissions.findById(id).orElseThrow(() -> new NoSuchElementException("submission " + id));
    }

    /** An id a new widget cannot take: in the library, or waiting for approval as another new widget. */
    public boolean idTaken(String id) {
        return library.exists(id) || openFor(id).isPresent();
    }

    public Optional<WidgetSubmission> openFor(String widgetId) {
        return submissions.findByWidgetIdAndStatusIn(widgetId, WidgetSubmission.OPEN).stream().findFirst();
    }

    public JsonNode spec(WidgetSubmission s) { return read(s.getSpec()); }

    /**
     * The Skills window for a role, as sections in display order. A builder: the widgets in progress (pending, sent
     * back, rejected), then approved, then stock. An approver: the widgets waiting for approval, then approved, then
     * stock. Everyone sees the current version number; the history is for approvers ({@link WidgetController}).
     */
    public List<Map<String, Object>> skills(Role role) {
        Map<String, WidgetSubmission> openByWidget = new HashMap<>();
        submissions.findByStatusIn(WidgetSubmission.OPEN).forEach(s -> openByWidget.put(s.getWidgetId(), s));
        List<Map<String, Object>> sections = new ArrayList<>();
        if (role == Role.APPROVER) {
            List<WidgetSubmission> waiting = submissions.findByStatusIn(List.of(WidgetSubmission.PENDING)).stream()
                    .sorted(Comparator.comparing(WidgetSubmission::getUpdatedAt)).toList();
            sections.add(section("review", "Needs approval", waiting.stream().map(this::item).toList()));
        } else {
            List<WidgetSubmission> mine = submissions.findByStatusIn(List.of(WidgetSubmission.PENDING, WidgetSubmission.CHANGES_REQUESTED,
                    WidgetSubmission.REJECTED)).stream().sorted(Comparator.comparing(WidgetSubmission::getUpdatedAt).reversed()).toList();
            sections.add(section("mine", "My widgets", mine.stream().map(this::item).toList()));
        }
        List<WidgetDefinition> all = library.list();
        sections.add(section("approved", "Approved", all.stream().filter(d -> !WidgetDefinition.STOCK.equals(d.getOrigin()))
                .map(d -> item(d, openByWidget.get(d.getId()))).toList()));
        sections.add(section("stock", "Stock", all.stream().filter(d -> WidgetDefinition.STOCK.equals(d.getOrigin()))
                .map(d -> item(d, openByWidget.get(d.getId()))).toList()));
        return sections;
    }

    /** What needs the role's attention: pending approvals for an approver, sent-back widgets for a builder. */
    public long attention(Role role) {
        return submissions.findByStatusIn(List.of(role == Role.APPROVER ? WidgetSubmission.PENDING : WidgetSubmission.CHANGES_REQUESTED)).size();
    }

    /** Validates a spec without saving it, for a live preview: errors, warnings and the first rows. */
    public ValidationReport preview(JsonNode spec, String layoutId, String pageId, Map<String, String> params) {
        Map<String, String> p = new LinkedHashMap<>(library.sampleParams(spec));
        if (params != null) params.forEach((k, v) -> { if (v != null && !v.isBlank()) p.put(k, v); });
        return validator.validate(spec, layoutId == null ? "default" : layoutId, pageId, p);
    }

    public Map<String, Object> item(WidgetSubmission s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "submission");
        m.put("id", s.getId());
        m.put("widgetId", s.getWidgetId());
        m.put("title", s.getTitle());
        m.put("description", s.getDescription());
        m.put("primitive", s.getPrimitive());
        m.put("status", s.getStatus());
        m.put("baseVersion", s.getBaseVersion());
        m.put("approvedVersion", s.getApprovedVersion());
        m.put("origin", s.getOrigin());
        m.put("sessionId", s.getSessionId());
        m.put("pageId", s.getPageId());
        m.put("comment", s.lastComment());
        m.put("updatedAt", s.getUpdatedAt());
        return m;
    }

    public static Map<String, Object> item(WidgetDefinition d, WidgetSubmission open) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("kind", "widget");
        m.put("id", d.getId());
        m.put("widgetId", d.getId());
        m.put("title", d.getTitle());
        m.put("description", d.getDescription());
        m.put("primitive", d.getPrimitive());
        m.put("status", "approved");
        m.put("version", d.getVersion());
        m.put("origin", d.getOrigin());
        m.put("params", d.getParams());
        m.put("pendingChange", open == null ? null : open.getId());
        m.put("updatedAt", d.getUpdatedAt());
        return m;
    }

    // ------------------------------------------------------------------ helpers
    private static Map<String, Object> section(String key, String title, List<Map<String, Object>> items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("title", title);
        m.put("items", items);
        return m;
    }

    private static void setSpec(WidgetSubmission s, WidgetLibrary.Checked c) {
        s.setSpec(c.spec().toString());
        s.setTitle(c.parsed().title());
        s.setDescription(c.parsed().description());
        s.setPrimitive(c.parsed().view().primitive());
    }

    private static void record(WidgetSubmission s, String type, Role role, String comment) {
        s.getHistory().add(new WidgetSubmission.Event(type, role.key(), comment, Instant.now()));
        s.setUpdatedAt(Instant.now());
    }

    private static String label(String status) { return status.replace('_', ' '); }

    private JsonNode read(String json) {
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("stored submission spec is not valid JSON", e);
        }
    }
}
