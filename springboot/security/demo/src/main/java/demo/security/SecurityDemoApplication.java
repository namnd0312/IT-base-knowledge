package demo.security;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Ung dung demo cho bo tai lieu Spring Security.
 *
 * Co 2 "the gioi" bao mat song song trong cung 1 app:
 *   - /api/**  : REST API stateless, xac thuc bang JWT (xem ApiSecurityConfig)
 *   - con lai  : trang web co session, dang nhap bang form (xem WebSecurityConfig)
 */
@SpringBootApplication
public class SecurityDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(SecurityDemoApplication.class, args);
    }
}
