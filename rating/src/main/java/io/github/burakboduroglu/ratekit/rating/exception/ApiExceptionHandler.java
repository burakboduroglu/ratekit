package io.github.burakboduroglu.ratekit.rating.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Turns exceptions into HTTP answers in one place, so controllers stay free of error handling. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(AccountNotFoundException.class)
    public ProblemDetail notFound(AccountNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler({AccountAlreadyExistsException.class, TopUpConflictException.class, TariffVersionExistsException.class})
    public ProblemDetail conflict(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
    }

    @ExceptionHandler(InvalidTariffException.class)
    public ProblemDetail invalidTariff(InvalidTariffException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    /** 422: the tariff itself is valid, but it cannot start at that time. */
    @ExceptionHandler(TariffInThePastException.class)
    public ProblemDetail tariffInThePast(TariffInThePastException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
