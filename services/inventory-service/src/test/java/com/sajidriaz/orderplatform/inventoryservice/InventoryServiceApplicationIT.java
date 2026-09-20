package com.sajidriaz.orderplatform.inventoryservice;

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
 * service's own schema, and Hibernate's {@code validate} agrees with the DDL — the check that
 * catches entity/migration drift before any behaviour test runs.
 *
 * <p>Named {@code *IT} because it needs Docker: the parent pom's surefire config excludes
 * {@code *IT} from the fast unit phase, and only failsafe ({@code ./mvnw verify}) runs it.
 */
@SpringBootTest
@Testcontainers
@EnabledIfEnvironmentVariable(named = "DOCKER_AVAILABLE", matches = "true")
class InventoryServiceApplicationIT {

    @Container
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17.6")
            .withDatabaseName("orderdb")
            .withUsername("appuser")
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
        // Schema per service (ADR-0007): the tables live in `inventory`, not in public.
        List<Map<String, Object>> tables = jdbc.queryForList("""
                select table_schema, table_name from information_schema.tables
                where table_name in ('stock_item', 'reservation', 'reservation_line',
                                     'outbox', 'processed_message')
                order by table_name
                """);

        assertThat(tables).hasSize(5);
        assertThat(tables).allSatisfy(row ->
                assertThat(row.get("table_schema"))
                        .as("tables must be owned by this service's own schema, found %s", row)
                        .isEqualTo("inventory"));

        // The seeded SKUs the order-service price catalog knows about.
        Integer seeded = jdbc.queryForObject(
                "select count(*) from inventory.stock_item where sku in ('SKU-1001', 'SKU-1002')", Integer.class);
        assertThat(seeded).isEqualTo(2);
    }

    @Test
    void theDatabaseItselfRefusesToOversell() {
        // The application's conditional UPDATE is what makes reservation correct, but the CHECK
        // constraint means even a direct SQL statement cannot reserve more than is on hand.
        jdbc.update("insert into inventory.stock_item (sku, on_hand, reserved) values ('SKU-CHECK', 2, 0)");

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() ->
                jdbc.update("update inventory.stock_item set reserved = 3 where sku = 'SKU-CHECK'")))
                .as("reserved must never be allowed to exceed on_hand")
                .isNotNull();

        Integer reserved = jdbc.queryForObject(
                "select reserved from inventory.stock_item where sku = 'SKU-CHECK'", Integer.class);
        assertThat(reserved).isZero();
    }
}
