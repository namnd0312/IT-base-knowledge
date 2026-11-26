package demo.security.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;

/**
 * Nguon user va cach bam mat khau.
 *
 * Spring Security chi can 2 bean nay la tu dung duoc DaoAuthenticationProvider:
 *   UserDetailsService (tim user theo username) + PasswordEncoder (so khop mat khau).
 */
@Configuration
public class UserAndPasswordConfig {

    /**
     * DelegatingPasswordEncoder: bam bang bcrypt va luu kem tien to "{bcrypt}",
     * nho do sau nay doi thuat toan ma mat khau cu van kiem tra duoc.
     */
    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * User luu trong RAM cho de hoc. Du an that: tu viet UserDetailsService doc tu database
     * (xem bai 03).
     */
    @Bean
    UserDetailsService userDetailsService(PasswordEncoder encoder) {
        return new InMemoryUserDetailsManager(
                User.withUsername("user")
                        .password(encoder.encode("password"))
                        .roles("USER")            // thanh authority "ROLE_USER"
                        .build(),
                User.withUsername("admin")
                        .password(encoder.encode("admin123"))
                        .roles("USER", "ADMIN")
                        .build());
    }

    /**
     * Lo AuthenticationManager ra thanh bean de AuthController goi truc tiep
     * khi xu ly POST /api/auth/login.
     */
    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }
}
