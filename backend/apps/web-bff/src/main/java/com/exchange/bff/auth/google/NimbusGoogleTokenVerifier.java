package com.exchange.bff.auth.google;

import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

/**
 * Verifies a Google ID token against Google's published signing keys.
 *
 * <p>Three checks matter, and only one of them is obvious:
 *
 * <ul>
 *   <li><b>Signature</b>, against Google's JWKS. Nimbus fetches and caches those and
 *       handles key rotation, which is the part worth not hand-rolling.
 *   <li><b>Issuer</b>, so a token minted by anything other than Google is refused.
 *   <li><b>Audience</b> -- and this is the one that is catastrophic to omit. Google signs
 *       ID tokens for every application that uses it. Without checking {@code aud},
 *       <em>any</em> valid Google token from <em>any</em> unrelated app would sign someone
 *       in here, and an attacker only needs to run their own Google app to mint one for a
 *       victim's account.
 * </ul>
 *
 * <p>Expiry is covered by {@code JwtValidators.createDefault()}, which Nimbus applies
 * along with the issuer check.
 *
 * <p>Absent unless {@code GOOGLE_CLIENT_ID} is set to something non-empty, so an
 * installation that does not use Google sign-in has no half-built verifier in its context
 * -- and the endpoint answers "not configured" rather than failing in a way that looks
 * like a bad token.
 */
@Component
// Non-EMPTY, not merely present. application.yml maps the property unconditionally, so it
// always exists -- as "" when GOOGLE_CLIENT_ID is unset. @ConditionalOnProperty would see
// that as configured and build a verifier whose audience check compares against the empty
// string, which is the one check that must never be vacuous.
@ConditionalOnExpression("!'${exchange.google.client-id:}'.isEmpty()")
public class NimbusGoogleTokenVerifier implements GoogleTokenVerifier {

    /** Google mints tokens with either spelling of the issuer, and has for years. Both
     *  are legitimate; accepting only one rejects real tokens intermittently. */
    private static final List<String> ISSUERS =
            List.of("https://accounts.google.com", "accounts.google.com");

    private static final String JWK_SET_URI = "https://www.googleapis.com/oauth2/v3/certs";

    private final JwtDecoder decoder;

    public NimbusGoogleTokenVerifier(@Value("${exchange.google.client-id}") String clientId) {
        NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(JWK_SET_URI).build();
        nimbus.setJwtValidator(new DelegatingValidator(clientId));
        this.decoder = nimbus;
    }

    @Override
    public GoogleIdentity verify(String idToken) {
        Jwt jwt;
        try {
            jwt = decoder.decode(idToken);
        } catch (JwtException e) {
            // Deliberately does not repeat the decoder's reason to the caller. Whether the
            // signature or the audience failed is exactly what someone forging one wants
            // to know; it belongs in our logs, not in a response.
            throw new InvalidGoogleTokenException("Google token failed verification", e);
        }

        String subject = jwt.getSubject();
        if (subject == null || subject.isBlank()) {
            throw new InvalidGoogleTokenException("Google token carries no subject");
        }
        return new GoogleIdentity(subject, jwt.getClaimAsString("email"),
                Boolean.TRUE.equals(jwt.getClaimAsBoolean("email_verified")));
    }

    /** The default validators (expiry, issuer) plus the audience check Google's own
     *  documentation calls out as required. */
    private static final class DelegatingValidator implements OAuth2TokenValidator<Jwt> {

        private final OAuth2TokenValidator<Jwt> delegate;

        private DelegatingValidator(String clientId) {
            this.delegate = new org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefault(),
                    new JwtClaimValidator<List<String>>(JwtClaimNames.AUD,
                            aud -> aud != null && aud.contains(clientId)),
                    new JwtClaimValidator<Object>(JwtClaimNames.ISS,
                            iss -> iss != null && ISSUERS.contains(iss.toString())));
        }

        @Override
        public org.springframework.security.oauth2.core.OAuth2TokenValidatorResult validate(
                Jwt token) {
            return delegate.validate(token);
        }
    }
}
