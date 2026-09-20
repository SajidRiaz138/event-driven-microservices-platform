package com.sajidriaz.orderplatform.common.security;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.util.List;

/**
 * Builds the platform's {@link JwtDecoder}: the one place that decides what "a valid access
 * token" means for every resource server in the platform (ADR-0009).
 *
 * <p>Four checks, all mandatory:
 * <ul>
 *   <li><b>signature</b> — RS256 only, verified against the issuer's JWKS, which the decoder
 *       fetches and caches by {@code kid}, so key rotation needs no redeployment. Pinning the
 *       algorithm matters: accepting whatever the token's header asks for is how {@code alg:none}
 *       and HMAC-with-the-public-key confusion attacks get in.</li>
 *   <li><b>issuer</b> — rejects a well-formed token minted by some other identity provider.</li>
 *   <li><b>audience</b> — rejects a token that is valid but was issued for a different relying
 *       party, so a token harvested from another system cannot be replayed here.</li>
 *   <li><b>expiry</b> — {@code exp}/{@code nbf} with the default clock skew.</li>
 * </ul>
 *
 * <p>This class lives in common-lib because "every service validates the token itself" is only
 * a safe design if they all validate it the same way; duplicating four validators per service is
 * how one of them silently ends up missing the audience check. It is compiled against Spring
 * Security, which common-lib declares as an <b>optional</b> dependency: services that are not
 * resource servers (payment-service, inventory-service — Kafka consumers with no customer-facing
 * HTTP surface) are unaffected and do not inherit Spring Security transitively.
 */
public final class PlatformJwtDecoders
{

    private PlatformJwtDecoders()
    {
    }

    /**
     * @param jwkSetUri JWKS endpoint of the issuer (Keycloak:
     *                  {@code {issuer}/protocol/openid-connect/certs})
     * @param issuer    expected {@code iss} claim, i.e. the realm URL
     * @param audience  expected entry in the {@code aud} claim
     */
    public static JwtDecoder rs256(String jwkSetUri, String issuer, String audience)
    {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri)
                .jwsAlgorithm(SignatureAlgorithm.RS256)
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(List.of(
                new JwtTimestampValidator(),
                new JwtIssuerValidator(issuer),
                audienceValidator(audience))));
        return decoder;
    }

    /**
     * Audience validator. Written as a lambda over the documented {@link OAuth2TokenValidator}
     * contract rather than leaning on a convenience class, so the failure it produces (an
     * {@code invalid_token} error, which the resource server renders as 401) is explicit.
     */
    static OAuth2TokenValidator<Jwt> audienceValidator(String audience)
    {
        return jwt ->
        {
            List<String> audiences = jwt.getAudience();
            if (audiences != null && audiences.contains(audience))
            {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "The required audience '" + audience + "' is missing from the token.",
                    "https://datatracker.ietf.org/doc/html/rfc9068#section-2.2"));
        };
    }
}
