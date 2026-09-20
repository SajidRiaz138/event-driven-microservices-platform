package com.sajidriaz.orderplatform.orderservice.security;

import com.sajidriaz.orderplatform.common.security.PlatformJwtDecoders;
import com.sajidriaz.orderplatform.common.security.PlatformScopes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * order-service as an OAuth2 resource server (ADR-0009).
 *
 * <p>Every service validates the access token independently — the gateway validating it at the
 * edge is a fail-fast convenience, not a trust boundary, so this chain repeats the work rather
 * than assuming it was done. {@link PlatformJwtDecoders} supplies the decoder so the four
 * checks (RS256 signature via JWKS, issuer, audience, expiry) are identical in every service.
 *
 * <p>Authorization is layered, and the split matters:
 * <ul>
 *   <li><b>Scope</b> decides what kind of operation is allowed — {@code orders:write} to place,
 *       {@code orders:read} to view. Enforced here, and insufficient scope is a 403.</li>
 *   <li><b>Ownership</b> decides which orders the caller may see, and stays in the domain
 *       (a caller asking for somebody else's order gets 404, never 403: a 403 would confirm
 *       the order exists, S-15).</li>
 * </ul>
 *
 * <p>{@code orders:write:any} (place an order for another customer) is accepted on the write
 * path so an administrative caller is not locked out by the scope check; acting on the
 * delegated identity is a separate, audited feature and is not implemented yet.
 *
 * <p>Sessions are stateless and CSRF protection is off: this is a bearer-token API with no
 * cookies, so there is no ambient credential for a browser to be tricked into replaying — the
 * attack CSRF tokens defend against does not apply, while a CSRF filter would reject every
 * legitimate POST from a non-browser client.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig
{

    private final String jwkSetUri;
    private final String issuerUri;
    private final String audience;

    public SecurityConfig(
                          @Value ("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
                          @Value ("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
                          @Value ("${order-platform.security.jwt.audience}") String audience)
    {
        this.jwkSetUri = jwkSetUri;
        this.issuerUri = issuerUri;
        this.audience = audience;
    }

    /**
     * Declaring this bean also takes the decoder out of Spring Boot's hands deliberately: the
     * auto-configured one would validate the signature, issuer and expiry but not the
     * audience, which is the check that stops a token minted for another relying party from
     * being replayed here.
     */
    @Bean
    public JwtDecoder jwtDecoder()
    {
        return PlatformJwtDecoders.rs256(jwkSetUri, issuerUri, audience);
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtDecoder jwtDecoder,
                                                   ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
                                                   ProblemDetailAccessDeniedHandler accessDeniedHandler) throws Exception
    {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        // Liveness/readiness and metrics are scraped by infrastructure that
                        // holds no user token. Unchanged from before this service had a
                        // filter chain at all; hardening the actuator surface is a separate
                        // decision from authenticating the customer API.
                        .requestMatchers("/actuator/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/orders")
                        .hasAnyAuthority(PlatformScopes.AUTHORITY_ORDERS_WRITE,
                                PlatformScopes.AUTHORITY_ORDERS_WRITE_ANY)
                        .requestMatchers(HttpMethod.GET, "/api/v1/orders/**")
                        .hasAuthority(PlatformScopes.AUTHORITY_ORDERS_READ)
                        .anyRequest()
                        .authenticated())
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                        .jwt(jwt -> jwt.decoder(jwtDecoder)))
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler));
        return http.build();
    }
}
