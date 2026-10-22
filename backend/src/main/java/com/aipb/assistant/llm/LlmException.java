package com.aipb.assistant.llm;

/** A Claude call failed (transport, API error, or a refusal); the message is fit to show the banker. */
public class LlmException extends RuntimeException {
    public LlmException(String message) { super(message); }
    public LlmException(String message, Throwable cause) { super(message, cause); }
}
