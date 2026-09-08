package dev.harshith.housing.api;

/** The caller's role does not permit this action. Rendered as HTTP 403. */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
