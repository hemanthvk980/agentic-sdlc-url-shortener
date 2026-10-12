package io.github.hemanthvk980.shortener.common;

import io.github.hemanthvk980.shortener.link.service.ConflictException;
import io.github.hemanthvk980.shortener.link.service.ForbiddenException;
import io.github.hemanthvk980.shortener.link.service.InvalidUrlException;
import io.github.hemanthvk980.shortener.link.service.LinkGoneException;
import io.github.hemanthvk980.shortener.link.service.LinkNotFoundException;
import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Every error is an RFC 7807 problem document; internal details never leak to clients.
 * Ordered first so these handlers win over Boot's ProblemDetailsExceptionHandler (enabled via
 * spring.mvc.problemdetails), which still covers framework errors not handled here (405, 415, ...).
 */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(LinkNotFoundException.class)
    ProblemDetail notFound(LinkNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Link not found", e.getMessage());
    }

    @ExceptionHandler(LinkGoneException.class)
    ProblemDetail gone(LinkGoneException e) {
        return problem(HttpStatus.GONE, "Link no longer available", e.getMessage());
    }

    @ExceptionHandler(InvalidUrlException.class)
    ProblemDetail invalid(InvalidUrlException e) {
        return problem(HttpStatus.BAD_REQUEST, "Invalid request", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail conflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler(ForbiddenException.class)
    ProblemDetail forbidden(ForbiddenException e) {
        return problem(HttpStatus.FORBIDDEN, "Forbidden", e.getMessage());
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ProblemDetail integrity(DataIntegrityViolationException e) {
        log.info("unique constraint race: {}", e.getMostSpecificCause().getMessage());
        return problem(HttpStatus.CONFLICT, "Conflict", "the code or idempotency key was taken concurrently; retry the request");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail bodyValidation(MethodArgumentNotValidException e) {
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "Validation failed", "request body is invalid");
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(f -> errors.putIfAbsent(f.getField(), f.getDefaultMessage()));
        pd.setProperty("errors", errors);
        return pd;
    }

    @ExceptionHandler({HandlerMethodValidationException.class, ConstraintViolationException.class})
    ProblemDetail paramValidation(Exception e) {
        return problem(HttpStatus.BAD_REQUEST, "Validation failed", "request parameters are invalid");
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail unreadable(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "Malformed request", "request body could not be parsed");
    }

    @ExceptionHandler(IllegalStateException.class)
    ProblemDetail unavailable(IllegalStateException e) {
        log.error("service error", e);
        return problem(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable", "please retry");
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        return pd;
    }
}
