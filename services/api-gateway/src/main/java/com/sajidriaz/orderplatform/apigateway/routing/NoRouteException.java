package com.sajidriaz.orderplatform.apigateway.routing;

/**
 * No route claims the requested path. Reported as 404: from a client's point of view the path
 * simply does not exist at the edge, and saying anything more would describe the internal route
 * table.
 */
public class NoRouteException extends RuntimeException
{

    public NoRouteException(String path)
    {
        super("No route matches " + path);
    }
}
