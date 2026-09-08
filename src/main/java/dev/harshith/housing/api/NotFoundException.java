package dev.harshith.housing.api;

/** The requested entity does not exist. Rendered as HTTP 404. */
public class NotFoundException extends RuntimeException {

    public NotFoundException(String message) {
        super(message);
    }
}
