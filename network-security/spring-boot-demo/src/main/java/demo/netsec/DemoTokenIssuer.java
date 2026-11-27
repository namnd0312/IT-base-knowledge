package demo.netsec;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.stereotype.Component;

/**
 * DEMO ONLY: plays the authorization server so the demo is self-contained.
 *
 * In a real system this class does not exist in the resource server. Tokens come from Keycloak,
 * Auth0, Okta, Spring Authorization Server..., the resource server only ever holds the PUBLIC key
 * (usually fetched from the issuer's JWKS endpoint via spring.security.oauth2.resourceserver.jwt.issuer-uri).
 */
@Component
public class DemoTokenIssuer {

    private final RSAPublicKey publicKey;
    private final NimbusJwtEncoder encoder;
    private final String issuer;

    public DemoTokenIssuer(@Value("${app.jwt.issuer}") String issuer) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        KeyPair pair = generator.generateKeyPair(); // new key on every start: old tokens stop validating
        this.publicKey = (RSAPublicKey) pair.getPublic();
        RSAKey jwk = new RSAKey.Builder(publicKey).privateKey((RSAPrivateKey) pair.getPrivate()).keyID("demo-key").build();
        this.encoder = new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(jwk)));
        this.issuer = issuer;
    }

    public RSAPublicKey publicKey() {
        return publicKey;
    }

    public String issue(String subject, String audience, String scope, Duration ttl) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(issuer)
                .subject(subject)
                .audience(java.util.List.of(audience))
                .issuedAt(now)
                .expiresAt(now.plus(ttl))
                .claim("scope", scope)
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).keyId("demo-key").build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }
}
