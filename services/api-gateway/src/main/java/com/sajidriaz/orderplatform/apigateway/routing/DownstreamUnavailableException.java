package com.sajidriaz.orderplatform.apigateway.routing;

/**
 * A downstream service could not be reached, or did not answer in time.
 *
 * <p>The distinction is kept because the two mean different things to a caller: a connection
 * failure (502) is safe to retry immediately, while a timeout (504) may mean the request was
 * received and is still being processed — retrying a non-idempotent call after a timeout can
 * duplicate work. It is also why every write on this API takes an {@code Idempotency-Key}.
 */
public class DownstreamUnavailableException extends RuntimeException
{

    private final boolean timeout;

    public DownstreamUnavailableException(String routeId, boolean timeout, Throwable cause)
    {
        super("Route '" + routeId + "' " + (timeout ? "timed out" : "could not be reached"), cause);
        this.timeout = timeout;
    }

    public boolean isTimeout()
    {
        return timeout;
    }
}
