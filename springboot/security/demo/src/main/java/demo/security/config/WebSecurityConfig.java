package demo.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Filter chain cho phan web truyen thong: dang nhap bang form, giu trang thai bang session cookie.
 *
 * Khong co securityMatcher -> bat moi request con lai (nhung gi khong phai /api/**).
 * CSRF de mac dinh (BAT) vi trinh duyet tu dong gui cookie JSESSIONID.
 */
@Configuration
public class WebSecurityConfig {

    @Bean
    @Order(2)
    SecurityFilterChain webSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/", "/error").permitAll()
                        .anyRequest().authenticated())
                // Trang /login do Spring sinh san; POST /login do UsernamePasswordAuthenticationFilter xu ly
                .formLogin(Customizer.withDefaults())
                // POST /logout: huy session, xoa SecurityContext, chuyen ve /login?logout
                .logout(Customizer.withDefaults());
        return http.build();
    }
}
