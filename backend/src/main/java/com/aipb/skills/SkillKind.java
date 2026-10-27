package com.aipb.skills;

import com.aipb.widget.WidgetDefinition;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * One kind of skill the assistant builds (widget; later insight and action): its stages after Intake, the artifact
 * they produce, how a proposal is assembled and how it is saved. The orchestrator owns everything kinds share -
 * Intake, locking, streaming, recording, approval - and hands a kind the run once Intake says to proceed.
 */
public interface SkillKind {

    String id();

    /** The stages after Intake, in order, with the banker-facing label of each. */
    Map<String, String> stageLabels();

    /** Whether templates of this kind may name action handlers. */
    default boolean hasHandlers() { return false; }

    /** Builds and proposes from Intake's output (proceed), or revises the current proposal. */
    void proceed(SkillRun run, JsonNode intakeOut, String bankerMessage, StageOps ops);

    /** The banker asked to verify the current proposal. */
    void verify(SkillRun run, StageOps ops);

    /** An existing library item that already answers the request, as a proposal. */
    ObjectNode reuseProposal(WidgetDefinition d, Map<String, String> params);

    /** Saves the session's approved proposal to the owner's personal library; returns the definition id. */
    String save(SkillRun run);
}
