package com.aipb.source;

/** The source data could not be read: the MCP server is unreachable, or it rejected the call (with its reason). */
public class SourceException extends RuntimeException {
    public SourceException(String message) { super(message); }
    public SourceException(String message, Throwable cause) { super(message, cause); }
}
