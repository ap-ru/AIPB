package com.aipb.identity;

/**
 * What a person may do in the app. No login yet: the role comes from the X-AIPB-Role header (see {@link ActorResolver}),
 * chosen from a dropdown for demos. Builder is the maker, Reviewer the checker of the maker-checker pair.
 */
public enum Role {
    BANKER("Banker", "Views and arranges dashboards."),
    BUILDER("Builder", "Builds widgets with the assistant and submits them to the global library for review."),
    REVIEWER("Reviewer", "Everything a Builder can do, and approves or rejects widgets submitted for review.");

    private final String label;
    private final String description;

    Role(String label, String description) {
        this.label = label;
        this.description = description;
    }

    public String id() { return name().toLowerCase(); }
    public String label() { return label; }
    public String description() { return description; }

    public boolean canUseAssistant() { return this != BANKER; }
    public boolean canAuthor() { return this != BANKER; }
    public boolean canReview() { return this == REVIEWER; }

    /** The role named by a header value; anything missing or unknown is a Banker. */
    public static Role parse(String value) {
        if (value == null) return BANKER;
        for (Role r : values()) if (r.id().equalsIgnoreCase(value.trim())) return r;
        return BANKER;
    }
}
