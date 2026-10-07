package io.github.burakboduroglu.ratekit.ingest.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns exceptions into HTTP answers in one place, so controllers stay free of error handling. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EventPublishException.class)
    public ProblemDetail publishFailed(EventPublishException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, "event could not be stored, retry");
    }

    /** 422, not 400: the body is well-formed, but its time cannot be billed. Retrying will not help. */
    @ExceptionHandler(EventTimeOutOfRangeException.class)
    public ProblemDetail eventTimeOutOfRange(EventTimeOutOfRangeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
