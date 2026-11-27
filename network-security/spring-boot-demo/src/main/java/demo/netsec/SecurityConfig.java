package demo.netsec;

import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Two ways a caller proves who it is, on one HTTPS port:
 *
 *   /internal/**  service-to-service: the TLS layer already verified a client certificate (mTLS);
 *                 Spring maps its CN to a principal and authorises by allow-list.
 *   /api/**       end users through a client app: an RS256 JWT issued by an authorization server.
 */
@Configuration
public class SecurityConfig {

    /** Services allowed to call /internal/**, identified by the CN of their CA-signed certificate. */
    private static final Set<String> TRUSTED_SERVICES = Set.of("order-service");

    @Bean
    @Order(1)
    SecurityFilterChain internalChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/internal/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().hasRole("SERVICE"))
                // X509AuthenticationFilter reads the certificate Tomcat already validated against the truststore.
                // No certificate -> no authentication -> 403 from the rule above.
                .x509(x509 -> x509
                        .subjectPrincipalRegex("CN=(.*?)(?:,|$)")
                        .userDetailsService(trustedServices()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable()); // no cookies are used on this chain
        return http.build();
    }

    @Bean
    @Order(2)
    SecurityFilterChain apiChain(HttpSecurity http) throws Exception {
        http.securityMatcher("/api/**", "/dev/**")
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/dev/token").permitAll() // demo-only stand-in for an authorization server
                        .requestMatchers("/api/admin/**").hasAuthority("SCOPE_orders:admin")
                        .anyRequest().hasAuthority("SCOPE_orders:read"))
                // BearerTokenAuthenticationFilter -> JwtDecoder (signature + validators) -> scopes become authorities
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .csrf(csrf -> csrf.disable()); // bearer tokens are not sent automatically by browsers
        return http.build();
    }

    /**
     * Signature check with the issuer's PUBLIC key, RS256 only, then the claim checks:
     * exp/nbf (with 60 s clock skew), iss, and aud. Without the audience check, a genuine token
     * issued for another API would be accepted here.
     */
    @Bean
    JwtDecoder jwtDecoder(DemoTokenIssuer issuer,
                          @Value("${app.jwt.issuer}") String issuerUri,
                          @Value("${app.jwt.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey(issuer.publicKey())
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();
        OAuth2TokenValidator<Jwt> audienceValidator =
                new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains(audience));
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuerUri), audienceValidator));
        return decoder;
    }

    private UserDetailsService trustedServices() {
        return commonName -> {
            if (!TRUSTED_SERVICES.contains(commonName)) {
                throw new UsernameNotFoundException("service not allowed: " + commonName);
            }
            return User.withUsername(commonName).password("{noop}n/a").roles("SERVICE").build();
        };
    }
}
