package com.sajidriaz.orderplatform.apigateway.security;

import com.sajidriaz.orderplatform.apigateway.config.GatewayProperties;
import com.sajidriaz.orderplatform.apigateway.ratelimit.RateLimitFilter;
import com.sajidriaz.orderplatform.apigateway.ratelimit.TokenBucketRateLimiter;
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
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Edge authentication and authorization.
 *
 * <p>The gateway validates the access token so an invalid one is refused here rather than after a
 * round trip to a service. It is explicitly <b>not</b> a trust boundary: every downstream service
 * validates the same token again (ADR-0009), and this configuration is written so that removing
 * it would cost latency, not safety.
 *
 * <p>Public by design:
 * <ul>
 *   <li>the <b>token route</b> — a client cannot present a token in order to obtain one;</li>
 *   <li><b>health and metrics</b> — scraped by infrastructure that holds no user token.</li>
 * </ul>
 *
 * <p>Everything else needs a valid token plus the scope for the operation: {@code orders:write} to
 * place, {@code orders:read} to view. This is the coarse half of a layered check — which
 * <em>orders</em> a caller may see is resource-level ownership and stays in order-service, which
 * answers 404 (never 403) for somebody else's order so that existence is not revealed (S-15).
 * Duplicating ownership logic here would mean two places to get it wrong, and the gateway has no
 * business reading the order table.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig
{

    private final String jwkSetUri;
    private final String issuerUri;
    private final GatewayProperties gatewayProperties;

    public SecurityConfig(
                          @Value ("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwkSetUri,
                          @Value ("${spring.security.oauth2.resourceserver.jwt.issuer-uri}") String issuerUri,
                          GatewayProperties gatewayProperties)
    {
        this.jwkSetUri = jwkSetUri;
        this.issuerUri = issuerUri;
        this.gatewayProperties = gatewayProperties;
    }

    /** The same four checks every service applies — RS256 signature via JWKS, issuer, audience, expiry. */
    @Bean
    public JwtDecoder jwtDecoder()
    {
        return PlatformJwtDecoders.rs256(jwkSetUri, issuerUri, gatewayProperties.audience());
    }

    @Bean
    public TokenBucketRateLimiter rateLimiter()
    {
        GatewayProperties.RateLimit rateLimit = gatewayProperties.rateLimit();
        return new TokenBucketRateLimiter(rateLimit.capacity(), rateLimit.refillPerSecond());
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtDecoder jwtDecoder,
                                                   TokenBucketRateLimiter rateLimiter,
                                                   ProblemDetailAuthenticationEntryPoint authenticationEntryPoint,
                                                   ProblemDetailAccessDeniedHandler accessDeniedHandler)
                                                                                                         throws Exception
    {
        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/actuator/**")
                        .permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/token")
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

        if (gatewayProperties.rateLimit().enabled())
        {
            // After authentication so the bucket can be keyed by `sub`, and constructed here
            // rather than published as a Filter bean: Spring Boot registers every Filter bean
            // with the servlet container as well, which would apply the limit twice and charge
            // two tokens per request.
            http.addFilterAfter(new RateLimitFilter(rateLimiter), BearerTokenAuthenticationFilter.class);
        }
        return http.build();
    }
}
