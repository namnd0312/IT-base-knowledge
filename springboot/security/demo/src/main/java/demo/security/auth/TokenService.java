package demo.security.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import demo.security.config.JwtConfig;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/** Phat access token (JWT) cho user da xac thuc thanh cong. */
@Service
public class TokenService {

    private final JwtEncoder jwtEncoder;
    private final Duration ttl;

    public TokenService(JwtEncoder jwtEncoder, @Value("${app.jwt.ttl-minutes}") long ttlMinutes) {
        this.jwtEncoder = jwtEncoder;
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public IssuedToken issue(Authentication authentication) {
        Instant now = Instant.now();
        List<String> roles = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .toList();

        // Payload cua JWT chi duoc ma hoa base64, AI CUNG DOC DUOC -> khong nhet du lieu nhay cam
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer("spring-security-study-demo")
                .subject(authentication.getName())
                .issuedAt(now)
                .expiresAt(now.plus(ttl)) // song ngan: token da phat thi khong thu hoi duoc
                .claim(JwtConfig.ROLES_CLAIM, roles)
                .build();

        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        String token = jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(token, ttl.toSeconds());
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
