package com.sajidriaz.orderplatform.orderservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Order-service entry point: REST API + saga orchestrator + transactional outbox
 * (ADR-0003, ADR-0004). {@link EnableScheduling} activates the outbox relay poller.
 */
@SpringBootApplication
@EnableScheduling

public class OrderServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(OrderServiceApplication.class, args);
	}

}
