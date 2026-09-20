package com.sajidriaz.orderplatform.inventoryservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * inventory-service: a saga participant (ADR-0003). Consumes {@code ReserveStock} and
 * {@code ReleaseStock} commands, replies with inventory events, and commits reservations when an
 * order is confirmed.
 *
 * <p>{@code @EnableScheduling} is required, not optional: without it the outbox relay never
 * publishes and the reservation TTL sweeper never runs, so the service would accept commands,
 * change stock, and reply to nobody.
 */
@SpringBootApplication
@EnableScheduling
public class InventoryServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(InventoryServiceApplication.class, args);
    }
}
