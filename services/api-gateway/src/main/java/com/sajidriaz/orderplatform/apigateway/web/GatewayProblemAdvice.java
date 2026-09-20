package com.sajidriaz.orderplatform.apigateway.web;

import com.sajidriaz.orderplatform.apigateway.routing.DownstreamUnavailableException;
import com.sajidriaz.orderplatform.apigateway.routing.NoRouteException;
import com.sajidriaz.orderplatform.common.observability.CorrelationContext;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

/**
 * Gateway-level errors as RFC 9457 {@code application/problem+json} (REST-API-GUIDE §5).
 *
 * <p>Same body shape as the services', including the required {@code correlationId}: a client
 * should not be able to tell from the error format whether a failure happened at the edge or
 * behind it. Downstream responses — including their error bodies — pass through untouched;
 * these handlers only cover failures the gateway itself produces.
 */
@RestControllerAdvice
public class GatewayProblemAdvice
{

    private static final Logger log = LoggerFactory.getLogger(GatewayProblemAdvice.class);
    private static final String PROBLEM_BASE = "https://docs.platform.local/problems/";

    @ExceptionHandler (NoRouteException.class)
    public ResponseEntity<ProblemDetail> handleNoRoute(NoRouteException e, HttpServletRequest request)
    {
        log.debug("No route for {} {}", request.getMethod(), request.getRequestURI());
        return problem(HttpStatus.NOT_FOUND, "not-found", "Not found",
                "The requested resource was not found.", request);
    }

    @ExceptionHandler (DownstreamUnavailableException.class)
    public ResponseEntity<ProblemDetail> handleDownstreamUnavailable(DownstreamUnavailableException e,
                                                                     HttpServletRequest request)
    {
        log.warn("Downstream failure handling {} {}: {}",
                request.getMethod(), request.getRequestURI(), e.getMessage());
        return e.isTimeout()
                ? problem(HttpStatus.GATEWAY_TIMEOUT, "downstream-timeout", "Gateway timeout",
                        "The upstream service did not respond in time.", request)
                : problem(HttpStatus.BAD_GATEWAY, "downstream-unavailable", "Bad gateway",
                        "The upstream service is unavailable.", request);
    }

    @ExceptionHandler (Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception e, HttpServletRequest request)
    {
        log.error("Unexpected error handling {} {}", request.getMethod(), request.getRequestURI(), e);
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
}
