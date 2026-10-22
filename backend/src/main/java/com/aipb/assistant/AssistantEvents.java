package com.aipb.assistant;

/**
 * Progress of a run, streamed to the banker as it happens (server-sent events in the API; a recorder in tests).
 * Events: stage (a stage started or finished), activity (what the stage is doing), message (the assistant's reply),
 * proposal (a widget ready for approval), error.
 */
public interface AssistantEvents {

    void emit(String event, Object data);

    AssistantEvents NONE = (event, data) -> { };
}
