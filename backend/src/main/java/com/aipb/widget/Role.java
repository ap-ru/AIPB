package com.aipb.widget;

/**
 * Who is acting in the maker-checker flow. A builder makes widgets, which wait for approval; an approver can make
 * widgets (approved as they are saved) and approve, send back, reject and restore. The role comes from the
 * X-AIPB-Role request header (the UI's role toggle) - a demo stand-in for real sign-in, not a security boundary.
 * A missing or unknown value is a builder: the role with fewer rights.
 */
public enum Role {
    BUILDER, APPROVER;

    public static final String HEADER = "X-AIPB-Role";

    public static Role of(String header) {
        return header != null && header.trim().equalsIgnoreCase("approver") ? APPROVER : BUILDER;
    }

    public String key() { return name().toLowerCase(); }

    /** An action this role may not take. */
    public static class Forbidden extends RuntimeException {
        public Forbidden(String message) { super(message); }
    }

    public void requireApprover(String action) {
        if (this != APPROVER) throw new Forbidden("only an approver can " + action);
    }
}
