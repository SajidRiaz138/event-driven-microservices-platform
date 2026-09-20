package com.sajidriaz.orderplatform.common.observability;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * Correlation context for the three-pillar observability model (ADR-0013).
 *
 * <p>Puts {@code correlationId}, {@code traceId} and {@code tenantId} into the SLF4J MDC
 * so every structured log line carries them, and provides helpers to propagate the
 * correlation id across REST headers and Kafka message headers. The correlation id is a
 * stable <em>business</em> identifier for one flow (e.g. one order); it is distinct from
 * the W3C {@code traceId}, which is the technical distributed-tracing id.
 */
public final class CorrelationContext {

    public static final String CORRELATION_ID = "correlationId";
    public static final String TRACE_ID = "traceId";
    public static final String TENANT_ID = "tenantId";

    /** HTTP/Kafka header carrying the business correlation id. */
    public static final String HEADER_CORRELATION_ID = "X-Correlation-Id";

    private CorrelationContext() {
    }

    /** Return the current correlation id from the MDC, or {@code null} if none is set. */
    public static String currentCorrelationId() {
        return MDC.get(CORRELATION_ID);
    }

    /**
     * Adopt the supplied correlation id, or generate a fresh one when the input is null
     * or blank. The value is placed in the MDC and returned.
     */
    public static String adoptOrGenerate(String incoming) {
        String id = (incoming == null || incoming.isBlank()) ? UUID.randomUUID().toString() : incoming;
        MDC.put(CORRELATION_ID, id);
        return id;
    }

    /** Set the tenant id in the MDC (defaults to {@code "default"} until multi-tenancy, ADR-0011). */
    public static void setTenant(String tenantId) {
        MDC.put(TENANT_ID, (tenantId == null || tenantId.isBlank()) ? "default" : tenantId);
    }

    /** Set the trace id in the MDC for cross-pillar correlation (ADR-0013). */
    public static void setTraceId(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(TRACE_ID, traceId);
        }
    }

    /** Clear all correlation keys from the MDC. Call at the end of request/message handling. */
    public static void clear() {
        MDC.remove(CORRELATION_ID);
        MDC.remove(TRACE_ID);
        MDC.remove(TENANT_ID);
    }
}
