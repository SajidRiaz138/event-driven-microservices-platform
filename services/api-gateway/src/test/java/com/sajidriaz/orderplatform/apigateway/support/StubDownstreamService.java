package com.sajidriaz.orderplatform.apigateway.support;

import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A stand-in for a downstream service, so the gateway's forwarding can be asserted on both
 * sides: the response the client receives, and the request the service actually received.
 *
 * <p>The second half is the interesting one. Whether the {@code Authorization} header and the
 * correlation id survive the hop is exactly what a gateway can silently get wrong while still
 * returning a plausible 200 — a token dropped in transit would only surface as an unexplained
 * 401 from a service, far from the cause.
 *
 * <p>Started on a daemon thread: {@link HttpServer}'s dispatcher inherits its daemon flag from
 * whichever thread starts it, and a non-daemon one keeps the test JVM alive after the suite ends
 * (which Failsafe reports as having to kill the fork 30 seconds after exit).
 */
public final class StubDownstreamService {

    private final HttpServer server;
    private final AtomicReference<ReceivedRequest> lastRequest = new AtomicReference<>();
    private final AtomicInteger requestCount = new AtomicInteger();
    private volatile int responseStatus = 202;
    private volatile String responseBody = "{\"status\":\"PENDING\"}";

    private StubDownstreamService(HttpServer server) {
        this.server = server;
    }

    public static StubDownstreamService start() {
        AtomicReference<HttpServer> started = new AtomicReference<>();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Thread starter = new Thread(() -> {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                    Thread worker = new Thread(runnable, "stub-downstream");
                    worker.setDaemon(true);
                    return worker;
                }));
                server.start();
                started.set(server);
            } catch (IOException e) {
                failure.set(new IllegalStateException("Could not start the stub downstream", e));
            }
        }, "stub-downstream-starter");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the stub downstream", e);
        }
        if (failure.get() != null) {
            throw failure.get();
        }

        StubDownstreamService stub = new StubDownstreamService(started.get());
        started.get().createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            stub.lastRequest.set(new ReceivedRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().toString(),
                    Map.copyOf(exchange.getRequestHeaders()),
                    new String(body, StandardCharsets.UTF_8)));
            stub.requestCount.incrementAndGet();
            byte[] responseBytes = stub.responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.getResponseHeaders().add("X-Downstream-Marker", "order-service-stub");
            exchange.sendResponseHeaders(stub.responseStatus, responseBytes.length);
            try (OutputStream responseBody = exchange.getResponseBody()) {
                responseBody.write(responseBytes);
            }
        });
        return stub;
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public ReceivedRequest lastRequest() {
        return lastRequest.get();
    }

    public int requestCount() {
        return requestCount.get();
    }

    public void reset() {
        lastRequest.set(null);
        requestCount.set(0);
    }

    public void respondWith(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }

    /**
     * @param method  HTTP method as received downstream
     * @param uri     request URI as received downstream, including query string
     * @param headers headers as received downstream (keys are canonicalised by the JDK server)
     * @param body    request body as received downstream
     */
    public record ReceivedRequest(String method, String uri, Map<String, List<String>> headers, String body) {

        /** First value of {@code name}, or {@code null} — header names are case-insensitive. */
        public String header(String name) {
            return headers.entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().isEmpty() ? null : entry.getValue().get(0))
                    .findFirst()
                    .orElse(null);
        }
    }
}
