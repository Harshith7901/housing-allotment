package dev.harshith.housing.api;

/**
 * The action is legitimate but not in this phase of the scheme — editing an application
 * after the roll is frozen, running a draw before the seed is committed, publishing twice.
 * Rendered as HTTP 409 Conflict rather than 400, because the request is well formed and
 * the problem is the state of the world.
 */
public class PhaseViolationException extends RuntimeException {

    public PhaseViolationException(String message) {
        super(message);
    }
}
