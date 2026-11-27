package demo.netsec;

import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DemoController {

    private final DemoTokenIssuer tokenIssuer;

    public DemoController(DemoTokenIssuer tokenIssuer) {
        this.tokenIssuer = tokenIssuer;
    }

    /** mTLS endpoint: shows the identity established by the TLS handshake. */
    @GetMapping("/internal/inventory")
    Map<String, Object> inventory(Authentication authentication, HttpServletRequest request) {
        X509Certificate[] chain = (X509Certificate[]) request.getAttribute("jakarta.servlet.request.X509Certificate");
        return Map.of(
                "caller", authentication.getName(),
                "authorities", authentication.getAuthorities().toString(),
                "certificateSubject", chain[0].getSubjectX500Principal().getName(),
                "certificateIssuer", chain[0].getIssuerX500Principal().getName(),
                "tlsProtocol", String.valueOf(request.getAttribute("jakarta.servlet.request.secure_protocol")),
                "items", 42);
    }

    /** JWT endpoint: the token is already verified; only its claims are read here. */
    @GetMapping("/api/orders")
    Map<String, Object> orders(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("subject", jwt.getSubject(), "audience", jwt.getAudience(),
                "scope", jwt.getClaimAsString("scope"), "orders", 3);
    }

    @GetMapping("/api/admin/orders")
    Map<String, Object> adminOrders(@AuthenticationPrincipal Jwt jwt) {
        return Map.of("subject", jwt.getSubject(), "allOrders", 1200);
    }

    /** DEMO ONLY: hands out tokens so curl can exercise the resource server. */
    @PostMapping("/dev/token")
    Map<String, String> token(@RequestParam String subject,
                              @RequestParam(defaultValue = "orders-api") String audience,
                              @RequestParam(defaultValue = "orders:read") String scope,
                              @RequestParam(defaultValue = "900") long ttlSeconds) {
        return Map.of("access_token", tokenIssuer.issue(subject, audience, scope, Duration.ofSeconds(ttlSeconds)));
    }
}
