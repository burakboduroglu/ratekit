package io.github.burakboduroglu.ratekit.ingest.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.Instant;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EventRequestTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();
    private static final String MAX = "x".repeat(64);
    private static final String TOO_LONG = "x".repeat(65);

    @Test
    void idsAndMetersUpTo64CharactersAreValid() {
        assertThat(violations(new EventRequest(MAX, MAX, MAX, 1, Instant.EPOCH))).isEmpty();
    }

    @Test
    void longerIdsAndMetersAreRefused() {
        Set<ConstraintViolation<EventRequest>> violations =
                violations(new EventRequest(TOO_LONG, TOO_LONG, TOO_LONG, 1, Instant.EPOCH));

        assertThat(violations).extracting(v -> v.getPropertyPath().toString())
                .containsExactlyInAnyOrder("eventId", "accountId", "meter");
    }

    private static Set<ConstraintViolation<EventRequest>> violations(EventRequest request) {
        return VALIDATOR.validate(request);
    }
}
