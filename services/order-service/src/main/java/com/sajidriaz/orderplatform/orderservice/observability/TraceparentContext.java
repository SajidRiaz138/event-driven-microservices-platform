package com.sajidriaz.orderplatform.orderservice.observability;

/**
 * Carries the inbound W3C {@code traceparent} for the duration of one request so that
 * messages produced while handling it can stamp it onto the Avro {@code Envelope}
 * (ADR-0010's envelope field, ADR-0013's requirement that trace context propagates
 * across REST → Kafka → DB).
 *
 * <p>Why this lives in order-service rather than {@code common-lib}: the shared
 * {@code CorrelationContext} models only {@code correlationId}, {@code traceId} and
 * {@code tenantId}, and has no slot for the full {@code traceparent} header value.
 * Adding one would mean editing {@code shared/common-lib}, which is out of scope for
 * this task, so the header is held locally instead.
 *
 * <p>Scope limit, stated plainly: this <em>propagates</em> a traceparent supplied by the
 * caller; it does not synthesise one when absent, because minting a trace id without a
 * real tracer would fabricate spans that no backend ever recorded. Envelopes for requests
 * that arrive without a traceparent therefore still carry {@code null}, which the schema
 * permits. Generating trace context at the edge belongs with the gateway plus a real
 * tracing stack (ADR-0013).
 */
public final class TraceparentContext {

    /** W3C Trace Context header name (lower-case per the specification). */
    public static final String HEADER_TRACEPARENT = "traceparent";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceparentContext() {
    }

    /** Adopt the inbound traceparent, if the caller supplied a non-blank one. */
    public static void set(String traceparent) {
        if (traceparent != null && !traceparent.isBlank()) {
            CURRENT.set(traceparent);
        }
    }

    /** The current traceparent, or {@code null} when the caller supplied none. */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * Extracts the trace-id field of a {@code traceparent}
     * ({@code version-traceId-spanId-flags}), for MDC logging. Returns {@code null} when
     * the value is absent or not in the expected shape — a malformed header from a caller
     * must never break request handling.
     */
    public static String traceIdOf(String traceparent) {
        if (traceparent == null) {
            return null;
        }
        String[] parts = traceparent.split("-");
        return parts.length >= 3 && !parts[1].isBlank() ? parts[1] : null;
    }

    /** Clear the holder. Must run in a {@code finally} so threads are never reused dirty. */
    public static void clear() {
        CURRENT.remove();
    }
}
