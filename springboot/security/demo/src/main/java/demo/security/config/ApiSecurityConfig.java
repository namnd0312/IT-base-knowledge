package demo.security.config;

import demo.security.error.JsonSecurityErrorHandlers;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Filter chain cho REST API: stateless, xac thuc bang JWT trong header Authorization.
 *
 * @Order(1): duoc xet TRUOC chain cua web. Request nao khop securityMatcher("/api/**")
 * thi dung chain nay va khong bao gio roi xuong chain sau.
 */
@Configuration
@EnableMethodSecurity // bat @PreAuthorize / @PostAuthorize (xem NoteService)
public class ApiSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http,
                                               JwtAuthenticationConverter jwtAuthenticationConverter,
                                               JsonSecurityErrorHandlers errorHandlers) throws Exception {
        http
                .securityMatcher("/api/**")
                // Khong dung cookie de xac thuc -> khong co nguy co CSRF -> tat duoc (xem bai 05)
                .csrf(csrf -> csrf.disable())
                // Khong tao HttpSession, moi request tu mang token
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Luat duoc xet tu tren xuong, luat khop dau tien thang -> cu the truoc, chung sau
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                // Doc "Authorization: Bearer <jwt>", verify bang JwtDecoder, dung SecurityContext
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter))
                        .authenticationEntryPoint(errorHandlers) // token thieu/sai/het han -> 401 JSON
                        .accessDeniedHandler(errorHandlers))     // du token nhung thieu quyen -> 403 JSON
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(errorHandlers)
                        .accessDeniedHandler(errorHandlers));
        return http.build();
    }
}
