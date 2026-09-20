package com.sajidriaz.orderplatform.common.security;

/**
 * OAuth2 scope names and the Spring Security authorities they map to (ADR-0009, ADR-0018).
 *
 * <p>The gateway performs the coarse scope check at the edge and every resource server
 * repeats it independently; having one definition of the strings keeps the two from drifting
 * apart, which is exactly the failure mode that produces a gateway that allows what the
 * service rejects (or worse, the reverse).
 *
 * <p>Spring Security's {@code JwtGrantedAuthoritiesConverter} maps each entry of the
 * {@code scope} claim to an authority prefixed with {@code SCOPE_}; the {@code AUTHORITY_*}
 * constants are those mapped names, so an authorization rule never has to hand-assemble the
 * prefix.
 *
 * <p>Resource-level ownership is deliberately <b>not</b> expressed here: a scope says what
 * kind of operation a caller may perform, never which rows they may see. Ownership stays in
 * the owning service (order-service answers 404 for somebody else's order, S-15).
 */
public final class PlatformScopes
{

    /** View your own orders. */
    public static final String ORDERS_READ = "orders:read";

    /** Place an order as yourself. */
    public static final String ORDERS_WRITE = "orders:write";

    /** Place an order on behalf of another customer (administrative, audited). */
    public static final String ORDERS_WRITE_ANY = "orders:write:any";

    /** Authority prefix Spring Security derives from the {@code scope} claim. */
    public static final String AUTHORITY_PREFIX = "SCOPE_";

    public static final String AUTHORITY_ORDERS_READ = AUTHORITY_PREFIX + ORDERS_READ;
    public static final String AUTHORITY_ORDERS_WRITE = AUTHORITY_PREFIX + ORDERS_WRITE;
    public static final String AUTHORITY_ORDERS_WRITE_ANY = AUTHORITY_PREFIX + ORDERS_WRITE_ANY;

    private PlatformScopes()
    {
    }
}
