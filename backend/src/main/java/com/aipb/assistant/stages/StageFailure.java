package com.aipb.assistant.stages;

/** A stage could not reach its objective; the message says why, in words fit for the banker. */
public class StageFailure extends RuntimeException {
    public StageFailure(String message) { super(message); }
}
