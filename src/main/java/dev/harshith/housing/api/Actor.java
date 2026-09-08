package dev.harshith.housing.api;

import java.util.Set;

/**
 * Who is making the request. Threaded explicitly through every service call rather than
 * stashed in a thread local, so that a reader can see at each call site whose act is being
 * recorded, and so that the audit entry cannot silently lose its author.
 */
public record Actor(String id, Role role) {

    public Actor {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("an actor id is required on every request");
        }
        if (role == null) {
            throw new IllegalArgumentException("an actor role is required on every request");
        }
    }

    public static Actor system(String component) {
        return new Actor(component, Role.SCHEME_ADMIN);
    }

    public void require(Role... allowed) {
        Set<Role> permitted = Set.of(allowed);
        if (!permitted.contains(role)) {
            throw new ForbiddenException("role " + role + " may not perform this action; it requires one of "
                    + permitted);
        }
    }

    /**
     * Enforces maker–checker: the actor who performed an earlier step may not also be the
     * one who checks it.
     */
    public void requireDifferentFrom(String earlierActorId, String what) {
        if (earlierActorId != null && earlierActorId.equals(id)) {
            throw new ForbiddenException(what + " must be performed by somebody other than "
                    + earlierActorId + ", who performed the preceding step");
        }
    }
}
