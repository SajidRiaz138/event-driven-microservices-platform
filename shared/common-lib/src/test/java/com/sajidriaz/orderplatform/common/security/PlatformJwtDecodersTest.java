package com.sajidriaz.orderplatform.common.security;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlatformJwtDecodersTest {

    @Test
    void audienceValidator_acceptsATokenCarryingTheExpectedAudience() {
        OAuth2TokenValidatorResult result = PlatformJwtDecoders.audienceValidator("order-platform")
                .validate(jwtWithAudience(List.of("order-platform")));

        assertFalse(result.hasErrors());
    }

    @Test
    void audienceValidator_acceptsTheExpectedAudienceAlongsideOthers() {
        OAuth2TokenValidatorResult result = PlatformJwtDecoders.audienceValidator("order-platform")
                .validate(jwtWithAudience(List.of("account", "order-platform")));

        assertFalse(result.hasErrors());
    }

    @Test
    void audienceValidator_rejectsATokenMintedForSomebodyElse() {
        OAuth2TokenValidatorResult result = PlatformJwtDecoders.audienceValidator("order-platform")
                .validate(jwtWithAudience(List.of("some-other-system")));

        assertTrue(result.hasErrors());
        assertTrue(result.getErrors().iterator().next().getErrorCode().equals("invalid_token"));
    }

    @Test
    void audienceValidator_rejectsATokenWithNoAudienceAtAll() {
        assertTrue(PlatformJwtDecoders.audienceValidator("order-platform")
                .validate(jwtWithAudience(null))
                .hasErrors());
    }

    private Jwt jwtWithAudience(List<String> audience) {
        Jwt.Builder builder = Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .subject("3b3a382f-216b-4edf-8118-c93ac7db1a59")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(300));
        if (audience != null) {
            builder.audience(audience);
        } else {
            // Jwt requires at least one claim; use an unrelated one so `aud` stays absent.
            builder.claims(claims -> claims.put("scope", "orders:read"));
        }
        Jwt jwt = builder.build();
        // Sanity: the fixture must actually express what the test name claims.
        Map<String, Object> claims = jwt.getClaims();
        assertTrue(audience == null ? !claims.containsKey("aud") : claims.containsKey("aud"));
        return jwt;
    }
}
