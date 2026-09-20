package com.sajidriaz.orderplatform.orderservice.support;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

/**
 * Mints signed RS256 access tokens for integration tests, and serves the matching JWKS over
 * HTTP so the service resolves the verification key exactly as it does against Keycloak.
 *
 * <p>This is a stand-in for the issuer, not for the validation. The service under test runs its
 * real {@code JwtDecoder}: it fetches this JWKS over HTTP, selects the key by {@code kid},
 * verifies the RS256 signature and then checks issuer, audience and expiry. Only the signing
 * authority is local — which is what lets a test assert that a token signed by the
 * <em>wrong</em> key, or carrying the wrong audience, is rejected. Tokens issued by a real
 * Keycloak are covered separately, by the api-gateway's {@code KeycloakRealmImportIT}, which
 * starts the actual container and the version-controlled realm export.
 *
 * <p>Deliberately not a live Keycloak container here: these suites already start Postgres and
 * Kafka, and adding a third container (~30s of realm import) to every saga test would make the
 * slowest part of the build about token issuance, which these tests are not about.
 *
 * <p>The JWKS server binds to an ephemeral loopback port and is shared by every test class in
 * the module, started once on first use.
 */
public final class TestJwtIssuer {

    /** Issuer the tests configure the service to trust. Not a reachable URL, and need not be. */
    public static final String ISSUER = "https://test-issuer.local/realms/order-platform";

    /** Audience the service requires; matches the realm export's audience mapper. */
    public static final String AUDIENCE = "order-platform";

    /** Scopes a customer's token carries by default, mirroring the realm's default scopes. */
    public static final List<String> CUSTOMER_SCOPES = List.of("orders:read", "orders:write");

    private static final RSAKey SIGNING_KEY = generateKey("order-platform-test-key");

    /**
     * A key the service knows nothing about: its JWK is never published to the JWKS endpoint,
     * so a token signed with it must fail signature verification.
     */
    private static final RSAKey UNTRUSTED_KEY = generateKey("untrusted-key");

    private static HttpServer jwkSetServer;
    private static String jwkSetUri;

    private TestJwtIssuer() {
    }

    /** JWKS endpoint to point {@code spring.security.oauth2.resourceserver.jwt.jwk-set-uri} at. */
    public static synchronized String jwkSetUri() {
        if (jwkSetServer == null) {
            startJwkSetServer();
        }
        return jwkSetUri;
    }

    /** A valid customer token: correct issuer, audience and signature, with both order scopes. */
    public static String tokenFor(String subject) {
        return token(subject, CUSTOMER_SCOPES, AUDIENCE, Instant.now().plus(5, ChronoUnit.MINUTES), SIGNING_KEY);
    }

    /** A valid token carrying only the scopes given — for asserting insufficient-scope 403s. */
    public static String tokenWithScopes(String subject, String... scopes) {
        return token(subject, List.of(scopes), AUDIENCE, Instant.now().plus(5, ChronoUnit.MINUTES), SIGNING_KEY);
    }

    /** Correctly signed, but expired. */
    public static String expiredTokenFor(String subject) {
        return token(subject, CUSTOMER_SCOPES, AUDIENCE, Instant.now().minus(1, ChronoUnit.MINUTES), SIGNING_KEY);
    }

    /** Correctly signed and current, but minted for a different relying party. */
    public static String tokenForForeignAudience(String subject) {
        return token(subject, CUSTOMER_SCOPES, "some-other-system",
                Instant.now().plus(5, ChronoUnit.MINUTES), SIGNING_KEY);
    }

    /** Well-formed and unexpired, but signed by a key that is not in the published JWKS. */
    public static String tokenSignedByUntrustedKey(String subject) {
        return token(subject, CUSTOMER_SCOPES, AUDIENCE,
                Instant.now().plus(5, ChronoUnit.MINUTES), UNTRUSTED_KEY);
    }

    private static String token(String subject, List<String> scopes, String audience,
                                Instant expiresAt, RSAKey signingKey) {
        try {
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .subject(subject)
                    .issuer(ISSUER)
                    .audience(audience)
                    .issueTime(Date.from(Instant.now().minus(5, ChronoUnit.SECONDS)))
                    .expirationTime(Date.from(expiresAt))
                    .jwtID(UUID.randomUUID().toString())
                    .claim("scope", String.join(" ", scopes))
                    .claim("typ", "Bearer")
                    .claim("azp", "order-platform-web")
                    .build();
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256)
                            .type(JOSEObjectType.JWT)
                            .keyID(signingKey.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new RSASSASigner(signingKey));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Could not mint a test token", e);
        }
    }

    private static RSAKey generateKey(String keyId) {
        try {
            return new RSAKeyGenerator(2048)
                    .keyID(keyId)
                    .keyUse(KeyUse.SIGNATURE)
                    .algorithm(JWSAlgorithm.RS256)
                    .generate();
        } catch (Exception e) {
            throw new IllegalStateException("Could not generate a test signing key", e);
        }
    }

    private static void startJwkSetServer() {
        // Only the public half of the signing key is published — the same contract Keycloak's
        // JWKS endpoint offers. The untrusted key is deliberately absent.
        byte[] jwkSet = new JWKSet(SIGNING_KEY.toPublicJWK())
                .toString()
                .getBytes(StandardCharsets.UTF_8);
        // Started from a daemon thread on purpose. HttpServer's dispatcher thread inherits its
        // daemon flag from whichever thread calls start(), and a non-daemon one keeps the test
        // JVM alive after the suite finishes — which surfaces as Surefire/Failsafe reporting
        // that it had to kill the fork 30 seconds after System.exit(0). A test fixture should
        // not be able to hold the build open.
        Thread starter = new Thread(() -> {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(Executors.newCachedThreadPool(runnable -> {
                    Thread worker = new Thread(runnable, "test-jwks");
                    worker.setDaemon(true);
                    return worker;
                }));
                server.createContext("/jwks.json", exchange -> {
                    exchange.getResponseHeaders().add("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, jwkSet.length);
                    try (OutputStream body = exchange.getResponseBody()) {
                        body.write(jwkSet);
                    }
                });
                server.start();
                jwkSetServer = server;
                jwkSetUri = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks.json";
            } catch (IOException e) {
                throw new IllegalStateException("Could not start the test JWKS endpoint", e);
            }
        }, "test-jwks-starter");
        starter.setDaemon(true);
        starter.start();
        try {
            starter.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while starting the test JWKS endpoint", e);
        }
        if (jwkSetUri == null) {
            throw new IllegalStateException("The test JWKS endpoint did not start");
        }
    }
}
