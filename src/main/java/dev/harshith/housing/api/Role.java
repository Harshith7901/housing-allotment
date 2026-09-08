package dev.harshith.housing.api;

/**
 * The roles the scheme's own procedure recognises.
 *
 * <p>These are domain roles, not framework roles, and the separations between them are
 * substantive rules rather than convenience:
 *
 * <ul>
 *   <li>{@link #DATA_ENTRY} can create and correct applications but cannot verify one,
 *       and cannot decide a duplicate. The clerk who keys a form is not the person who
 *       decides it is genuine.</li>
 *   <li>{@link #VERIFIER} decides eligibility and adjudicates duplicate matches, but
 *       cannot freeze a roll or run a draw.</li>
 *   <li>{@link #SCHEME_ADMIN} runs the lifecycle — publish rules, freeze the roll, commit
 *       the seed, execute and publish — but cannot alter an application or a verification
 *       decision.</li>
 *   <li>{@link #AUDITOR} can read everything, including the raw audit chain, and can write
 *       nothing at all.</li>
 * </ul>
 *
 * <p>Authentication itself is deliberately out of scope for this exercise: the caller
 * asserts an identity and a role through request headers. That is <b>not</b> production
 * safe and the README says so plainly. What is production-shaped is that every service
 * method states the role it requires and every recorded act carries the actor, so
 * swapping the headers for a real identity provider changes one class.
 */
public enum Role {
    PUBLIC,
    APPLICANT,
    DATA_ENTRY,
    VERIFIER,
    SCHEME_ADMIN,
    AUDITOR
}
