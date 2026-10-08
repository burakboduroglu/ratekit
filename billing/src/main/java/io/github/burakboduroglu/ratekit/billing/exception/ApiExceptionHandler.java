package io.github.burakboduroglu.ratekit.billing.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns exceptions into HTTP answers in one place, so controllers stay free of error handling. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(InvalidPeriodException.class)
    public ProblemDetail invalidPeriod(InvalidPeriodException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(PeriodNotClosedException.class)
    public ProblemDetail periodNotClosed(PeriodNotClosedException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RatingNotCaughtUpException.class)
    public ProblemDetail ratingNotCaughtUp(RatingNotCaughtUpException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(ChargesInFlightException.class)
    public ProblemDetail chargesInFlight(ChargesInFlightException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(RatingProgressUnknownException.class)
    public ProblemDetail ratingProgressUnknown(RatingProgressUnknownException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage());
    }

    @ExceptionHandler(InvoiceNotFoundException.class)
    public ProblemDetail notFound(InvoiceNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }
}
