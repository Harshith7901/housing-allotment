package dev.harshith.housing.web;

import dev.harshith.housing.api.Actor;
import dev.harshith.housing.api.Role;

/**
 * Reads the caller's asserted identity from request headers.
 *
 * <p><b>This is not authentication.</b> The caller says who they are and the service
 * believes them, which is fine for a reviewable exercise and would be indefensible in
 * production. It is isolated here for exactly that reason: replacing it with a real
 * identity provider means changing this one class, because every service method already
 * takes an {@link Actor} and every recorded act already carries one.
 *
 * <p>What the headers do buy, even in this form, is that the audit chain names a person
 * for every act and the maker–checker rules actually bite — the officer who commits a seed
 * cannot execute the draw, and the clerk who keys a form cannot verify it.
 */
public final class CallerActor {

    public static final String ID_HEADER = "X-Actor-Id";
    public static final String ROLE_HEADER = "X-Actor-Role";

    private CallerActor() {
    }

    public static Actor of(String id, String role) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("the " + ID_HEADER + " header is required");
        }
        if (role == null || role.isBlank()) {
            throw new IllegalArgumentException("the " + ROLE_HEADER + " header is required");
        }
        Role parsed;
        try {
            parsed = Role.valueOf(role.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(ROLE_HEADER + " must be one of "
                    + java.util.Arrays.toString(Role.values()) + ", not " + role);
        }
        return new Actor(id.trim(), parsed);
    }
}
