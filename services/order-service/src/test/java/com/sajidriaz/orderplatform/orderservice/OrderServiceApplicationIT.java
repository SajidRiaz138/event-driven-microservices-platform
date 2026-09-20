package com.sajidriaz.orderplatform.orderservice;

import com.sajidriaz.orderplatform.orderservice.support.TestJwtIssuer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Smoke test: the Spring context loads with a real Postgres (Testcontainers) and
 * Flyway migrations apply cleanly. Named {@code *IT} (not {@code *Test}) because it
 * requires Docker — the parent pom's surefire config excludes {@code *IT} from the
 * fast unit-test phase (`./mvnw test`) and only failsafe (`./mvnw verify`) runs it.
 */
@SpringBootTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "true")
class OrderServiceApplicationIT {

	@Container
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6")
			.withDatabaseName("orderdb")
			.withUsername("appuser")
			.withPassword("testpass");

	@DynamicPropertySource
	static void datasourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);
		registry.add("spring.kafka.bootstrap-servers", () -> "PLAINTEXT://localhost:9092");
		// The service is an OAuth2 resource server now (ADR-0009), so the context needs an
		// issuer and a JWKS endpoint to build its JwtDecoder from. Pointed at the test issuer
		// rather than a live Keycloak: this test asserts the context starts, and nothing here
		// presents a token.
		registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", TestJwtIssuer::jwkSetUri);
		registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> TestJwtIssuer.ISSUER);
	}

	@Test
	void contextLoads() {
		// Verifies the full Spring context (JPA, Flyway, Kafka wiring) starts cleanly.
	}
}
