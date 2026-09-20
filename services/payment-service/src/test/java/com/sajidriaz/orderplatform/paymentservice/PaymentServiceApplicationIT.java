package com.sajidriaz.orderplatform.paymentservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Smoke test: the context loads against a real Postgres, Flyway applies the migration into this
 * service's own schema, and Hibernate's {@code validate} agrees with the DDL — the check that catches
 * entity/migration drift before any behaviour test runs.
 */
@SpringBootTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "true")
class PaymentServiceApplicationIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6")
            .withDatabaseName("orderdb")
            .withUsername("payment_svc")
            .withPassword("testpass");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.kafka.bootstrap-servers", () -> "PLAINTEXT://localhost:9092");
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void contextLoadsAndTheSchemaIsOwnedByThisService() {
        // Schema per service (ADR-0007): these tables live in `payment`, not in public.
        List<Map<String, Object>> tables = jdbc.queryForList("""
                select table_schema, table_name from information_schema.tables
                where table_name in ('payment_intent', 'payment_attempt', 'payment_operation',
                                     'outbox', 'processed_message')
                order by table_name
                """);

        assertThat(tables).hasSize(5);
        assertThat(tables).allSatisfy(row ->
                assertThat(row.get("table_schema"))
                        .as("tables must be owned by this service's own schema, found %s", row)
                        .isEqualTo("payment"));
    }

    @Test
    void theOperationsModelHasNoColumnCardDataCouldOccupy() {
        // ADR-0016 §4 / ADR-0009: no raw PAN is ever stored. Asserted structurally rather than by
        // reviewing code, so a future migration adding such a column fails this test.
        List<String> columns = jdbc.queryForList("""
                select column_name from information_schema.columns
                where table_schema = 'payment'
                order by column_name
                """, String.class);

        assertThat(columns).isNotEmpty();
        assertThat(columns).noneSatisfy(column -> assertThat(column.toLowerCase())
                .containsAnyOf("pan", "card_number", "cardnumber", "cvv", "cvc", "expiry"));
        // The only instrument reference is an opaque token.
        assertThat(columns).contains("payment_method_token");
    }

    @Test
    void captureOnceIsEnforcedByTheDatabase() {
        // The application checks for an existing capture before calling the provider, but a check in
        // application code can be lost to a race. This unique index is what makes capture-once hold
        // even then (ADR-0016 §2).
        List<String> indexes = jdbc.queryForList("""
                select indexname from pg_indexes
                where schemaname = 'payment' and tablename = 'payment_operation'
                """, String.class);

        assertThat(indexes).contains("uq_payment_operation_one_live_capture_per_attempt");
    }

    @Test
    void thereIsNoUniqueConstraintOnAnOrderIdInTheOperationsTable() {
        // ADR-0016's central correction: deduplicating payments on the order forbids the legitimate
        // second attempt after a declined card. This asserts the mistake has not crept back in.
        List<String> operationIndexes = jdbc.queryForList("""
                select indexdef from pg_indexes
                where schemaname = 'payment' and tablename = 'payment_operation'
                """, String.class);

        assertThat(operationIndexes).noneSatisfy(definition -> {
            assertThat(definition).contains("UNIQUE");
            assertThat(definition).contains("order_id");
        });
    }
}
