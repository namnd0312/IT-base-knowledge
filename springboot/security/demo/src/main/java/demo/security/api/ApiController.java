package demo.security.api;

import java.util.List;
import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Ba muc truy cap theo URL: cong khai, can dang nhap, can role ADMIN. */
@RestController
@RequestMapping("/api")
public class ApiController {

    /** permitAll - khong can token. */
    @GetMapping("/public/hello")
    public Map<String, String> hello() {
        return Map.of("message", "Xin chao, endpoint nay ai cung goi duoc");
    }

    /**
     * authenticated - can token hop le.
     * Authentication duoc Spring MVC tiem tu SecurityContextHolder cua request hien tai.
     */
    @GetMapping("/me")
    public Map<String, Object> me(Authentication authentication, @AuthenticationPrincipal Jwt jwt) {
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .sorted()
                .toList();
        return Map.of(
                "username", authentication.getName(),
                "authorities", authorities,
                "tokenExpiresAt", String.valueOf(jwt.getExpiresAt()));
    }

    /** hasRole('ADMIN') - luat dat o ApiSecurityConfig theo URL /api/admin/**. */
    @GetMapping("/admin/stats")
    public Map<String, Object> adminStats() {
        return Map.of("totalUsers", 2, "note", "Chi ADMIN thay duoc");
    }
}
