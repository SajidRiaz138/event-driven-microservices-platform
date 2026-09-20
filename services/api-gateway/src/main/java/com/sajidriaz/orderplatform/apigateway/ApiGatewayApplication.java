package com.sajidriaz.orderplatform.apigateway;

import com.sajidriaz.orderplatform.apigateway.config.GatewayProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * API gateway: the platform's single public entry point.
 *
 * <p>Responsibilities (ADR-0009, docs/api/REST-API-GUIDE.md):
 * <ul>
 *   <li>route by path prefix to the owning service, and expose the auth token route;</li>
 *   <li>validate the access token at the edge so a bad token is refused once, here, instead of
 *       after a round trip — while every downstream service still validates it itself;</li>
 *   <li>propagate the token and {@code X-Correlation-Id} (generating the latter when absent) so
 *       a request is traceable from the edge through Kafka to the database;</li>
 *   <li>rate limit per client, answering 429 with {@code Retry-After};</li>
 *   <li>report its own failures as RFC 9457 {@code application/problem+json}, the same contract
 *       the services use.</li>
 * </ul>
 *
 * <p>Servlet-based, on Boot 4.1.1's own dependencies, for the reasons recorded in this module's
 * pom: the Spring Cloud release train does not yet target Boot 4.1.
 */
@SpringBootApplication
@EnableConfigurationProperties (GatewayProperties.class)
public class ApiGatewayApplication
{

    public static void main(String[] args)
    {
        SpringApplication.run(ApiGatewayApplication.class, args);
    }
}
