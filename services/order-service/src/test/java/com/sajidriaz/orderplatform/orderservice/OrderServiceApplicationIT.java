package com.sajidriaz.orderplatform.orderservice;

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
	}

	@Test
	void contextLoads() {
		// Verifies the full Spring context (JPA, Flyway, Kafka wiring) starts cleanly.
	}
}
