package dev.harshith.housing.web;

import dev.harshith.housing.api.ForbiddenException;
import dev.harshith.housing.api.NotFoundException;
import dev.harshith.housing.api.PhaseViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps domain failures onto status codes, and takes some care over which is which.
 *
 * <p>The distinction that matters here is 409 versus 400. A request to correct an
 * application after the roll is frozen is not malformed — it is a perfectly good request
 * that the state of the world forbids — so it is a Conflict, and the message says which
 * phase blocked it and why. Collapsing every refusal into 400 would leave an operator
 * unable to tell "I sent the wrong field" from "you are too late".
 *
 * <p>{@link IllegalStateException} maps to 500 on purpose. In this codebase it is thrown
 * only by integrity checks — a roll that no longer matches its hash, a result that no
 * longer recomputes — and those are not client errors. They mean stored data has been
 * altered, and they are logged at error level for exactly that reason.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    public ProblemDetail notFound(NotFoundException e) {
        return detail(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    public ProblemDetail forbidden(ForbiddenException e) {
        return detail(HttpStatus.FORBIDDEN, "Not permitted for this role", e.getMessage());
    }

    @ExceptionHandler(PhaseViolationException.class)
    public ProblemDetail phase(PhaseViolationException e) {
        return detail(HttpStatus.CONFLICT, "Not permitted in the current phase", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail badRequest(IllegalArgumentException e) {
        return detail(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ProblemDetail missingHeader(MissingRequestHeaderException e) {
        return detail(HttpStatus.BAD_REQUEST, "Missing header",
                e.getHeaderName() + " is required; every request must state who is making it. "
                        + "Send " + CallerActor.ID_HEADER + " and " + CallerActor.ROLE_HEADER + ".");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException e) {
        List<String> problems = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(error ->
                problems.add(error.getField() + " " + error.getDefaultMessage()));
        e.getBindingResult().getGlobalErrors().forEach(error ->
                problems.add(error.getObjectName() + " " + error.getDefaultMessage()));
        ProblemDetail problem = detail(HttpStatus.BAD_REQUEST, "Validation failed",
                String.join("; ", problems));
        problem.setProperty("fieldErrors", problems);
        return problem;
    }

    /**
     * A uniqueness constraint refused the write.
     *
     * <p>Mapped to 409 rather than 500 because in this schema these constraints are
     * business rules, not incidental storage details: one live offer per flat per draw, one
     * ticket per application per roll, one audit event per predecessor hash. A caller
     * hitting one has lost a race with another operator, and Conflict is the honest answer.
     */
    @ExceptionHandler(org.springframework.dao.DataIntegrityViolationException.class)
    public ProblemDetail conflict(org.springframework.dao.DataIntegrityViolationException e) {
        log.warn("a uniqueness constraint refused a write: {}", e.getMostSpecificCause().getMessage());
        return detail(HttpStatus.CONFLICT, "Conflicting concurrent change",
                "The write was refused by a uniqueness constraint, which in this schema means a "
                        + "business rule was about to be broken - most likely another operator "
                        + "performed the same step concurrently. Re-read the current state and retry.");
    }

    /**
     * Integrity failures. These indicate the stored roll, rule set or result no longer
     * agrees with its recorded hash, which is a serious event rather than a bad request.
     */
    @ExceptionHandler(IllegalStateException.class)
    public ProblemDetail integrity(IllegalStateException e) {
        log.error("integrity check failed: {}", e.getMessage(), e);
        return detail(HttpStatus.INTERNAL_SERVER_ERROR, "Integrity check failed", e.getMessage());
    }

    private ProblemDetail detail(HttpStatus status, String title, String message) {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setTitle(title);
        problem.setDetail(message);
        return problem;
    }
}
