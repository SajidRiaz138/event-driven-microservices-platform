package com.sajidriaz.orderplatform.orderservice.web;

import com.sajidriaz.orderplatform.common.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.net.URI;
import java.util.List;
import java.util.Map;

/**
 * Maps every exception to an RFC 9457 {@code application/problem+json} body
 * (REST-API-GUIDE §5). {@code correlationId} is always present and matches the
 * {@code X-Correlation-Id} response header (set by {@link CorrelationIdFilter}
 * regardless of success/failure). Never leaks stack traces, SQL, or internal detail.
 */
@RestControllerAdvice
public class ProblemDetailExceptionHandler
{

    private static final Logger log = LoggerFactory.getLogger(ProblemDetailExceptionHandler.class);
    private static final String PROBLEM_BASE = "https://docs.platform.local/problems/";

    @ExceptionHandler (MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request)
    {
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        problem.setType(URI.create(PROBLEM_BASE + "validation-failed"));
        problem.setTitle("Validation failed");
        problem.setDetail("One or more fields are invalid.");
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("correlationId", CorrelationContext.currentCorrelationId());
        List<Map<String, String>> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(this::toFieldError)
                .toList();
        problem.setProperty("errors", errors);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .contentType(org.springframework.http.MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler (OrderValidationException.class)
    public ResponseEntity<ProblemDetail> handleOrderValidation(OrderValidationException ex, HttpServletRequest request)
    {
        return problem(HttpStatus.UNPROCESSABLE_ENTITY, "validation-failed", "Validation failed", ex.getMessage(), request);
    }

    @ExceptionHandler (UnauthenticatedException.class)
    public ResponseEntity<ProblemDetail> handleUnauthenticated(UnauthenticatedException ex, HttpServletRequest request)
    {
        return problem(HttpStatus.UNAUTHORIZED, "unauthenticated", "Unauthorized", "A valid credential is required.", request);
    }

    @ExceptionHandler (OrderNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleNotFound(OrderNotFoundException ex, HttpServletRequest request)
    {
        // S-15: existence must not be revealed, so the detail is generic regardless of
        // whether the order is missing or simply not owned by the caller.
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found", "The requested resource was not found.", request);
    }

    @ExceptionHandler (IdempotencyConflictException.class)
    public ResponseEntity<ProblemDetail> handleIdempotencyConflict(IdempotencyConflictException ex, HttpServletRequest request)
    {
        return problem(HttpStatus.CONFLICT, "idempotency-key-conflict", "Conflict", ex.getMessage(), request);
    }

    @ExceptionHandler (HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ProblemDetail> handleNotAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request)
    {
        return problem(HttpStatus.BAD_REQUEST, "malformed-request", "Bad request", "Malformed request.", request);
    }

    /**
     * A required header is absent — in practice {@code Idempotency-Key}, which the
     * OpenAPI contract marks required on POST /orders. This is a client error: without
     * this handler Spring's binding exception fell through to the catch-all below and
     * was reported as 500, telling the caller the server was broken when in fact their
     * request was incomplete.
     */
    @ExceptionHandler (MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest request)
    {
        // The header NAME is the caller's own input, not internal detail, so naming it is
        // actionable without leaking anything (REST-API-GUIDE §5).
        return problem(HttpStatus.BAD_REQUEST, "missing-header", "Bad request",
                "Required header '" + ex.getHeaderName() + "' is missing.", request);
    }

    /**
     * The body is absent, truncated, or not valid JSON, so it never reached bean
     * validation. Also previously surfaced as 500.
     */
    @ExceptionHandler (HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(HttpMessageNotReadableException ex, HttpServletRequest request)
    {
        // Deliberately does not echo ex.getMessage(): it can carry parser internals and
        // fragments of the payload.
        return problem(HttpStatus.BAD_REQUEST, "malformed-request", "Bad request",
                "The request body is missing or is not valid JSON.", request);
    }

    /**
     * A path variable or query parameter could not be converted to its target type —
     * e.g. an order id that is not a UUID. A client error, not a server fault.
     */
    @ExceptionHandler (MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request)
    {
        return problem(HttpStatus.BAD_REQUEST, "malformed-request", "Bad request",
                "Parameter '" + ex.getName() + "' has an invalid format.", request);
    }

    @ExceptionHandler (Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex, HttpServletRequest request)
    {
        // Never leak stack traces/SQL to the client; log server-side with correlation id.
        log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal server error",
                "An unexpected error occurred.", request);
    }

    private ResponseEntity<ProblemDetail> problem(HttpStatus status,
                                                  String typeSlug,
                                                  String title,
                                                  String detail,
                                                  HttpServletRequest request)
    {
        ProblemDetail problem = ProblemDetail.forStatus(status);
        problem.setType(URI.create(PROBLEM_BASE + typeSlug));
        problem.setTitle(title);
        problem.setDetail(detail);
        problem.setInstance(URI.create(request.getRequestURI()));
        problem.setProperty("correlationId", CorrelationContext.currentCorrelationId());
        return ResponseEntity.status(status)
                .header(HttpHeaders.CONTENT_TYPE, "application/problem+json")
                .body(problem);
    }

    private Map<String, String> toFieldError(FieldError fieldError)
    {
        return Map.of(
                "field", fieldError.getField(),
                "issue", fieldError.getDefaultMessage() == null ? "invalid" : fieldError.getDefaultMessage());
    }
}
