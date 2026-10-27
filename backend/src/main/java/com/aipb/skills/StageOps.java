package com.aipb.skills;

import com.aipb.assistant.AssistantEvents;
import com.aipb.assistant.stages.RunRecorder;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/** What the orchestrator lends a skill kind for one run: recorded stages, replies, proposals and the run's deadline. */
public interface StageOps {

    interface StageCall<T> { T call(RunRecorder rec); }

    /** Runs one stage: streams start/finish, records the run, saves the session. */
    <T> T stage(String name, StageCall<T> call);

    void reply(String text, String kind);

    void propose(ObjectNode proposal, String text);

    /** The run's progress stream (stage activity, acknowledgements). */
    AssistantEvents events();

    Instant deadline();
}
