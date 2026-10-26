package com.aipb.identity;

/**
 * Who is making a request. Until there is sign-in, each role is one demo person (demo-banker, demo-builder,
 * demo-reviewer); SSO later replaces only {@link ActorResolver}.
 */
public record Actor(String id, Role role) {

    public static Actor of(Role role) { return new Actor("demo-" + role.id(), role); }

    /** Creating or changing widgets, including with the assistant. */
    public Actor requireAuthor() {
        if (!role.canAuthor()) throw new Forbidden("the " + role.label() + " role cannot build widgets");
        return this;
    }

    /** Approving, giving feedback on, rejecting or restoring widgets: the checker's actions. */
    public Actor requireReviewer(String action) {
        if (!role.canReview()) throw new Forbidden("only a Reviewer can " + action);
        return this;
    }

    /** The request is not allowed for this actor's role (403). */
    public static class Forbidden extends RuntimeException {
        public Forbidden(String message) { super(message); }
    }
}
