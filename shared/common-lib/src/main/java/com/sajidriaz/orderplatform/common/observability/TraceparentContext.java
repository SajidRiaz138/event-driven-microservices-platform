package com.sajidriaz.orderplatform.common.observability;

/**
 * Carries the inbound W3C {@code traceparent} for the duration of one unit of work (an HTTP
 * request, or the handling of one Kafka record) so that messages produced while handling it
 * can stamp it onto the Avro {@code Envelope} — ADR-0013's requirement that trace context
 * propagates across REST → Kafka → DB.
 *
 * <p>Promoted into common-lib because it is needed identically by every service: the
 * orchestrator adopts it from an HTTP header, the saga participants adopt it from the
 * {@code Envelope.traceparent} of the command they are handling. order-service retains its
 * own equivalent class (left untouched); this one is the shared version new services use.
 *
 * <p>Scope limit, stated plainly: this <em>propagates</em> a traceparent, it does not
 * synthesise one when absent, because minting a trace id without a real tracer would
 * fabricate spans no backend ever recorded. Envelopes produced without an inbound
 * traceparent therefore carry {@code null}, which the schema permits.
 *
 * <p>Uses a {@link ThreadLocal}. ADR-0015 §5 warns against unbounded ThreadLocal
 * accumulation across virtual threads, which is why {@link #clear()} is mandatory in a
 * {@code finally} block at every entry point — the holder is scoped to one unit of work,
 * never left set on a pooled or reused carrier.
 */
public final class TraceparentContext {

    /** W3C Trace Context header name (lower-case per the specification). */
    public static final String HEADER_TRACEPARENT = "traceparent";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceparentContext() {
    }

    /** Adopt the inbound traceparent, if a non-blank one was supplied. */
    public static void set(String traceparent) {
        if (traceparent != null && !traceparent.isBlank()) {
            CURRENT.set(traceparent);
        }
    }

    /** The current traceparent, or {@code null} when none was supplied. */
    public static String current() {
        return CURRENT.get();
    }

    /**
     * Extracts the trace-id field of a {@code traceparent}
     * ({@code version-traceId-spanId-flags}), for MDC logging. Returns {@code null} when the
     * value is absent or not in the expected shape — a malformed header from a caller must
     * never break message or request handling.
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
