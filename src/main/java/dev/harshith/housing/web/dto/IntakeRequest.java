package dev.harshith.housing.web.dto;

import dev.harshith.housing.core.model.Channel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

/**
 * One application, from either channel.
 *
 * <p>{@code submittedAt} is accepted from the caller rather than taken from the server
 * clock, because a paper form keyed in on 3 May was submitted on 12 April and the earlier
 * date is the one that matters: it decides which of a household's duplicate forms carries
 * the ticket. Online submissions leave it null and get the server time.
 *
 * <p>{@code idempotencyKey} is how the applicant who pressed submit twice stops being a
 * data-quality problem. The same key returns the same application instead of creating a
 * second one.
 */
public record IntakeRequest(
        @NotBlank String schemeCode,
        @NotNull Channel channel,
        Instant submittedAt,
        @Size(max = 64) String batchId,
        @Size(max = 128) String idempotencyKey,
        @NotBlank @Size(max = 200) String fullName,
        @Size(max = 200) String relativeName,
        @Size(max = 32) String governmentId,
        @Size(max = 24) String phone,
        @Past LocalDate dateOfBirth,
        @Size(max = 500) String addressLine,
        @Size(max = 32) String wardCode,
        @PositiveOrZero int residencyYears,
        @NotBlank @Size(max = 32) String verticalCode,
        Set<String> horizontalCodes,
        List<String> unitTypePreferences
) {
}
