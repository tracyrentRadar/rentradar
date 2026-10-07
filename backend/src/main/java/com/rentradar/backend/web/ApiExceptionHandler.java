package com.rentradar.backend.web;

import com.rentradar.backend.ml.UnknownModelException;
import com.rentradar.backend.security.AuthenticationException;
import com.rentradar.backend.security.EmailAlreadyRegisteredException;
import com.rentradar.backend.service.LocationNotResolvableException;
import com.rentradar.backend.service.ServiceDegradedException;
import com.rentradar.backend.service.UnknownMarketException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail onAuthenticationFailure(AuthenticationException e) {
        return problem(HttpStatus.UNAUTHORIZED, "Authentication failed", e.getMessage());
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ProblemDetail onDuplicateEmail(EmailAlreadyRegisteredException e) {
        return problem(HttpStatus.CONFLICT, "Registration refused", e.getMessage());
    }

    @ExceptionHandler(UnknownMarketException.class)
    public ProblemDetail onUnknownMarket(UnknownMarketException e) {
        return problem(HttpStatus.BAD_REQUEST, "Unknown market", e.getMessage());
    }

    @ExceptionHandler(LocationNotResolvableException.class)
    public ProblemDetail onUnresolvableLocation(LocationNotResolvableException e) {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "Location not resolvable", e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ProblemDetail onIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail onValidationFailure(MethodArgumentNotValidException e) {
        Map<String, String> fields = e.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        error -> error.getField(),
                        error -> error.getDefaultMessage() == null
                                ? "is invalid" : error.getDefaultMessage(),
                        (first, second) -> first));

        ProblemDetail detail = problem(
                HttpStatus.BAD_REQUEST, "Validation failed", "One or more fields are invalid");
        detail.setProperty("fields", fields);
        return detail;
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail onUnexpected(Exception e) {
        // Framework exceptions carry their own status and body. Swallowing them
        // turns a 404 or a 405 into a 500 and buries the real cause in a log.
        if (e instanceof ErrorResponse known) {
            return known.updateAndGetBody(null, Locale.getDefault());
        }

        log.error("Unhandled exception", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR,
                "Internal error", "The request could not be completed");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }

    @ExceptionHandler(ServiceDegradedException.class)
    public ProblemDetail onDegraded(ServiceDegradedException e) {
        log.error("Inference unavailable", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Service degraded",
                "A price estimate could not be produced right now. Try again shortly.");
    }

    @ExceptionHandler(UnknownModelException.class)
    public ProblemDetail onUnknownModel(UnknownModelException e) {
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "No model available", e.getMessage());
    }
}