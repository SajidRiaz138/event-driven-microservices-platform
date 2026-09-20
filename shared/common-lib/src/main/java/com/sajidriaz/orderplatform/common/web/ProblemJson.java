package com.sajidriaz.orderplatform.common.web;

/**
 * Minimal RFC 9457 {@code application/problem+json} body writer for error paths that run
 * <b>outside</b> Spring MVC.
 *
 * <p>Spring's {@code ProblemDetail} plus {@code @RestControllerAdvice} already covers
 * everything the {@code DispatcherServlet} handles. It does not cover the security filter
 * chain: a missing or invalid bearer token is rejected by an {@code AuthenticationEntryPoint}
 * and an insufficient scope by an {@code AccessDeniedHandler}, both of which run before MVC
 * is ever reached and therefore have no message converters available. Those handlers need to
 * write the response body themselves, and every service that becomes an OAuth2 resource
 * server (ADR-0009) needs the same body shape — so the shape lives here rather than being
 * re-invented per service.
 *
 * <p>The output matches the platform error contract in docs/api/REST-API-GUIDE.md §5: the
 * standard RFC 9457 members plus the required {@code correlationId} extension.
 *
 * <p>Deliberately plain Java with no Spring, servlet or JSON-library dependency: common-lib
 * is consumed by services that do not have Spring Security on the classpath, and the field
 * set here is fixed and small enough that hand-writing it is safer than adding a dependency.
 */
public final class ProblemJson
{

    /** Base URI for problem types, matching the services' own handlers. */
    public static final String PROBLEM_BASE = "https://docs.platform.local/problems/";

    /** The media type this class produces. */
    public static final String CONTENT_TYPE = "application/problem+json";

    private ProblemJson()
    {
    }

    /**
     * Renders an RFC 9457 problem document.
     *
     * @param status        HTTP status code
     * @param typeSlug      last path segment of the problem {@code type} URI, e.g. {@code unauthenticated}
     * @param title         short, human-readable summary
     * @param detail        explanation of this occurrence; must not leak internals
     * @param instance      request path the problem occurred on
     * @param correlationId platform correlation id; omitted from the body when {@code null}
     * @return a JSON document suitable for an {@code application/problem+json} response body
     */
    public static String render(int status,
                                String typeSlug,
                                String title,
                                String detail,
                                String instance,
                                String correlationId)
    {
        StringBuilder json = new StringBuilder(256);
        json.append('{')
                .append("\"type\":\"")
                .append(escape(PROBLEM_BASE + typeSlug))
                .append("\",")
                .append("\"title\":\"")
                .append(escape(title))
                .append("\",")
                .append("\"status\":")
                .append(status)
                .append(',')
                .append("\"detail\":\"")
                .append(escape(detail))
                .append("\",")
                .append("\"instance\":\"")
                .append(escape(instance))
                .append('"');
        if (correlationId != null && !correlationId.isBlank())
        {
            json.append(",\"correlationId\":\"").append(escape(correlationId)).append('"');
        }
        return json.append('}').toString();
    }

    /**
     * Escapes a value for inclusion in a JSON string literal. Request paths and correlation
     * ids are caller-influenced, so this is a correctness requirement, not a nicety: an
     * unescaped quote would produce a malformed body and turn a clean 401 into a parse error
     * at the client.
     */
    private static String escape(String value)
    {
        if (value == null)
        {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++)
        {
            char c = value.charAt(i);
            switch (c)
            {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (c < 0x20)
                    {
                        escaped.append(String.format("\\u%04x", (int) c));
                    }
                    else
                    {
                        escaped.append(c);
                    }
                }
            }
        }
        return escaped.toString();
    }
}
